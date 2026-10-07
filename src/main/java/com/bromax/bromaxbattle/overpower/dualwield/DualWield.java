package com.bromax.bromaxbattle.overpower.dualwield;

import com.bromax.bromaxbattle.api.BhbHitEvent;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxbattle.overpower.combat.OverpowerManager;
import com.bromax.bromaxbattle.overpower.config.OpConfig;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-hand attacks, after Offhand Combat: with a BHB weapon in the off hand, right-click attacks
 * with it on its own cooldown (main-hand attacks keep theirs). The off-hand hit uses the off-hand
 * weapon's own damage, enchantments, durability and Overpower profile. Not available while the
 * main hand holds a two-handed weapon.
 */
public final class DualWield {
    private DualWield() {}

    private static final Map<UUID, Long> READY_AT = new ConcurrentHashMap<>();
    /** Set while an off-hand hit is being applied, so the damage trade-off reads the off-hand profile. */
    public static final ThreadLocal<Boolean> OFFHAND_HIT = ThreadLocal.withInitial(() -> false);

    /**
     * Whether this player can attack with the off hand at all (client and server): any BHB weapon
     * in the off hand, alongside anything in the main hand. A two-handed main weapon only gives up
     * its empty-off-hand damage bonus.
     */
    public static boolean canDualWield(Player player) {
        ItemStack off = player.getOffhandItem();
        if (off.isEmpty() || off.getItem() instanceof ShieldItem) return false;
        return WeaponRegistry.INSTANCE.getAttributes(off) != null;
    }

    /** Ticks between off-hand attacks: the vanilla full-charge time for the off-hand weapon's speed. */
    public static int lockTicks(ItemStack off) {
        double speed = 4.0;
        double[] mult = {0};
        double[] add = {0};
        off.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (!attr.equals(Attributes.ATTACK_SPEED)) return;
            if (mod.operation() == AttributeModifier.Operation.ADD_VALUE) add[0] += mod.amount();
            else mult[0] += mod.amount();
        });
        speed = (speed + add[0]) * (1 + mult[0]);
        if (speed <= 0) return 20;
        return (int) Math.max(4, Math.ceil(20.0 / speed - 0.5 - 1e-6));
    }

    public static void handle(ServerPlayer player, int targetId, int variant) {
        if (!canDualWield(player) || player.isSpectator()) return;
        long now = player.level().getGameTime();
        Long ready = READY_AT.get(player.getUUID());
        if (ready != null && now < ready) return;
        ItemStack off = player.getOffhandItem();
        READY_AT.put(player.getUUID(), now + lockTicks(off));
        com.bromax.bromaxbattle.combat.GuardHandler.lower(player, 0);
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(off);
        // The client picked the variant and is already animating it; the hit matches it
        int idx = variant >= 0 && variant < attrs.attacks.size() ? variant : 0;
        OpNetwork.offhandSwing(player, idx);
        player.swing(net.minecraft.world.InteractionHand.OFF_HAND, false);

        Entity e = targetId >= 0 ? player.level().getEntity(targetId) : null;
        if (!(e instanceof LivingEntity target) || !target.isAlive() || target == player) return;
        if (!player.canInteractWithEntity(target, 1.0)) return;

        // A Glare Strike can be thrown with either hand
        if (OverpowerManager.tryGlare(player, target, now)) return;

        AttackDefinition attack = attrs.attacks.isEmpty() ? null : attrs.attacks.get(idx);
        DamageSource src = player.damageSources().playerAttack(player);
        float base = offhandBaseDamage(player, off);
        float enchant = player.level() instanceof ServerLevel sl
                ? EnchantmentHelper.modifyDamage(sl, off, target, src, base) - base : 0f;
        boolean crit = player.getRandom().nextFloat() < 0.15f;
        float damage = (base + enchant) * (attack != null ? attack.damageMultiplier : 1f)
                * (crit ? 1.5f : 1f) * OpConfig.f(OpConfig.OFFHAND_DAMAGE);

        if (target.getLastHurtByMob() == player) target.invulnerableTime = 0;
        boolean landed;
        OFFHAND_HIT.set(true);
        try {
            landed = target.hurt(src, damage);
        } finally {
            OFFHAND_HIT.set(false);
        }
        if (!landed) return;
        double yaw = Math.toRadians(player.getYRot());
        target.knockback(0.25, Math.sin(yaw), -Math.cos(yaw));
        off.hurtAndBreak(1, player, EquipmentSlot.OFFHAND);
        if (crit) player.crit(target);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                crit ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1f, 1.1f);
        NeoForge.EVENT_BUS.post(new BhbHitEvent(player, target, attack, attrs.category, crit));
    }

    /** Player base 1 + the off-hand weapon's flat damage + Strength (as the main hand would get). */
    private static float offhandBaseDamage(Player player, ItemStack off) {
        float[] base = {1.0f};
        off.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (attr.equals(Attributes.ATTACK_DAMAGE) && mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
                base[0] += (float) mod.amount();
            }
        });
        var str = player.getEffect(MobEffects.DAMAGE_BOOST);
        if (str != null) base[0] += 3.0f * (str.getAmplifier() + 1);
        return Math.max(0f, base[0]);
    }

    public static void clear(UUID id) {
        READY_AT.remove(id);
    }
}
