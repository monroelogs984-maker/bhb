package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatHandler {

    public static CombatHandler INSTANCE;

    private final Map<UUID, Long>  attackReadyAtTick = new ConcurrentHashMap<>();
    private final Map<UUID, Long>  lastCombatHitTick = new ConcurrentHashMap<>();
    private final Map<UUID, Float> pendingDamageMult = new ConcurrentHashMap<>();

    private static final long  COMBAT_WINDOW_TICKS   = 160L;
    private static final float COMBAT_HEAL_MULT      = 0.8f;
    private static final float DUAL_WIELD_DMG_MULT   = 0.8f;
    private static final float TWO_HANDED_FOCUS_MULT = 1.15f;

    private static final class PendingHit {
        final Player   attacker;
        final Entity   primaryTarget;
        final float    combinedMult;
        final float    aoeDamage;
        final WeaponCategory category;
        final long     fireAtTick;

        PendingHit(Player attacker, Entity primaryTarget,
                   float combinedMult, float aoeDamage,
                   WeaponCategory category, long fireAtTick) {
            this.attacker       = attacker;
            this.primaryTarget  = primaryTarget;
            this.combinedMult   = combinedMult;
            this.aoeDamage      = aoeDamage;
            this.category       = category;
            this.fireAtTick     = fireAtTick;
        }
    }

    private static final class PendingOffhandHit {
        final Player   attacker;
        final Entity   target;
        final float    damage;
        final float    aoeDamage;
        final WeaponCategory category;
        final long     fireAtTick;

        PendingOffhandHit(Player attacker, Entity target,
                          float damage, float aoeDamage,
                          WeaponCategory category, long fireAtTick) {
            this.attacker   = attacker;
            this.target     = target;
            this.damage     = damage;
            this.aoeDamage  = aoeDamage;
            this.category   = category;
            this.fireAtTick = fireAtTick;
        }
    }

    private final Map<UUID, PendingHit>        pendingHits        = new ConcurrentHashMap<>();
    private final Map<UUID, PendingOffhandHit> pendingOffhandHits = new ConcurrentHashMap<>();
    private final Set<UUID>                    pendingHitRefiring = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Boolean> clientOffhandTurn = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> serverOffhandTurn = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (!player.level.isClientSide) return;
        if (AnimationController.INSTANCE.isPlaying(player.getUUID())) return;
        UUID pid = player.getUUID();
        if (isDualWielding(player)) {
            clientOffhandTurn.put(pid, !clientOffhandTurn.getOrDefault(pid, false));
        }
        triggerAnimation(player, true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onAttackEntityServer(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level.isClientSide) return;
        if (player.isDeadOrDying()) return;
        if (event.getTarget() == null) return;

        UUID pid = player.getUUID();
        if (pendingHitRefiring.contains(pid)) return;

        long now    = player.level.getGameTime();
        Long readyAt = attackReadyAtTick.get(pid);
        if (readyAt != null && now < readyAt) {
            event.setCanceled(true);
            return;
        }

        try {
            long lockTicks = calcLockTicks(player);
            attackReadyAtTick.put(pid, now + lockTicks);

            if (isDualWielding(player)) {
                boolean wasOffhand = serverOffhandTurn.getOrDefault(pid, false);
                serverOffhandTurn.put(pid, !wasOffhand);

                if (wasOffhand) {
                    event.setCanceled(true);
                    try {
                        ItemStack offStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
                        WeaponAttributes offAttrs = WeaponRegistry.INSTANCE.getAttributes(offStack);
                        float offVariantMult  = 1.0f;
                        int   offHitDelay     = 0;
                        WeaponCategory offCat = null;
                        if (offAttrs != null && !offAttrs.attacks.isEmpty()) {
                            int offIdx = ComboTracker.INSTANCE.pickAttack(
                                    pid, offAttrs, player.tickCount + 1);
                            AttackDefinition offVariant = offAttrs.attacks.get(offIdx);
                            offVariantMult = offVariant.damageMultiplier;
                            offHitDelay    = offVariant.hitDelay;
                            offCat         = offAttrs.category;
                        }
                        float offBase   = calcOffhandBaseDamage(player, offStack);
                        float offDamage = (offBase + getEnchantBonus(offStack, event.getTarget()))
                                          * offVariantMult * DUAL_WIELD_DMG_MULT;
                        float offAoe    = 0f;
                        if (offCat != null && AoeCalculator.hasAoe(offCat)) {
                            offAoe = offBase * AoeCalculator.getDamageMult(offCat)
                                     * offVariantMult * DUAL_WIELD_DMG_MULT;
                        }
                        pendingOffhandHits.put(pid, new PendingOffhandHit(
                                player, event.getTarget(),
                                offDamage, offAoe, offCat,
                                now + Math.max(offHitDelay, 1)));
                    } catch (Exception e) {
                        BromaxBattle.LOGGER.warn("[BHB] Offhand scheduling failed: {}", e.getMessage());
                    }
                    return;
                }
            }

            WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
            int   idx               = 0;
            float variantDamageMult = 1.0f;
            int   hitDelay          = 0;
            if (attrs != null && !attrs.attacks.isEmpty()) {
                idx = ComboTracker.INSTANCE.pickAttack(pid, attrs, player.tickCount);
                AttackDefinition variant = attrs.attacks.get(idx);
                variantDamageMult = variant.damageMultiplier;
                hitDelay          = variant.hitDelay;
            }

            float situationMult = isDualWielding(player) ? DUAL_WIELD_DMG_MULT
                                : isFocused(player)      ? TWO_HANDED_FOCUS_MULT
                                : 1.0f;
            float combinedMult = variantDamageMult * situationMult;

            if (hitDelay > 0) {
                event.setCanceled(true);
                float aoeDamage = 0f;
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    AttributeInstance dmgAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        aoeDamage = (float) dmgAttr.getValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * combinedMult;
                    }
                }
                pendingHits.put(pid, new PendingHit(
                        player, event.getTarget(),
                        combinedMult, aoeDamage,
                        attrs != null ? attrs.category : null,
                        now + hitDelay));
            } else {
                if (combinedMult != 1.0f) pendingDamageMult.put(pid, combinedMult);
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    AttributeInstance dmgAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        float aoeDamage = (float) dmgAttr.getValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * combinedMult;
                        if (aoeDamage > 0) {
                            List<LivingEntity> aoeTargets = AoeCalculator.getTargets(
                                    player, event.getTarget(), attrs.category);
                            for (LivingEntity t : aoeTargets) {
                                t.hurt(DamageSource.playerAttack(player), aoeDamage);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            BromaxBattle.LOGGER.warn("[BHB] Attack processing failed: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!pendingHits.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingHit>> it = pendingHits.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, PendingHit> entry = it.next();
                PendingHit hit = entry.getValue();
                if (hit.attacker.level.getGameTime() < hit.fireAtTick) continue;
                it.remove();
                if (hit.attacker.isDeadOrDying() || hit.primaryTarget.isRemoved()) continue;

                UUID pid = hit.attacker.getUUID();
                if (hit.combinedMult != 1.0f) pendingDamageMult.put(pid, hit.combinedMult);

                pendingHitRefiring.add(pid);
                try {
                    // In 1.19.2, Player.attack() is public — no accessor mixin needed
                    hit.attacker.attack(hit.primaryTarget);
                } catch (Exception e) {
                    BromaxBattle.LOGGER.warn("[BHB] Pending hit re-fire failed: {}", e.getMessage());
                    pendingDamageMult.remove(pid);
                } finally {
                    pendingHitRefiring.remove(pid);
                }

                if (hit.aoeDamage > 0 && hit.category != null) {
                    try {
                        List<LivingEntity> aoeTargets = AoeCalculator.getTargets(
                                hit.attacker, hit.primaryTarget, hit.category);
                        for (LivingEntity t : aoeTargets) {
                            t.hurt(DamageSource.playerAttack(hit.attacker), hit.aoeDamage);
                        }
                    } catch (Exception e) {
                        BromaxBattle.LOGGER.warn("[BHB] Pending AOE failed: {}", e.getMessage());
                    }
                }
            }
        }

        if (!pendingOffhandHits.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingOffhandHit>> offIt = pendingOffhandHits.entrySet().iterator();
            while (offIt.hasNext()) {
                Map.Entry<UUID, PendingOffhandHit> entry = offIt.next();
                PendingOffhandHit hit = entry.getValue();
                if (hit.attacker.level.getGameTime() < hit.fireAtTick) continue;
                offIt.remove();
                if (hit.attacker.isDeadOrDying() || hit.target.isRemoved()) continue;

                try {
                    hit.target.hurt(DamageSource.playerAttack(hit.attacker), hit.damage);
                } catch (Exception e) {
                    BromaxBattle.LOGGER.warn("[BHB] Offhand hit failed: {}", e.getMessage());
                }

                if (hit.aoeDamage > 0 && hit.category != null) {
                    try {
                        List<LivingEntity> aoeTargets = AoeCalculator.getTargets(
                                hit.attacker, hit.target, hit.category);
                        for (LivingEntity t : aoeTargets) {
                            t.hurt(DamageSource.playerAttack(hit.attacker), hit.aoeDamage);
                        }
                    } catch (Exception e) {
                        BromaxBattle.LOGGER.warn("[BHB] Offhand AOE failed: {}", e.getMessage());
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getSource() == null) return;
        Entity src = event.getSource().getEntity();
        if (!(src instanceof Player attacker)) return;
        if (attacker.level.isClientSide) return;
        Float mult = pendingDamageMult.remove(attacker.getUUID());
        if (mult != null && mult != 1.0f) {
            event.setAmount(event.getAmount() * mult);
        }
    }

    @SubscribeEvent
    public void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level.isClientSide) return;
        if (event.getSource() == null) return;
        if (!(event.getSource().getEntity() instanceof LivingEntity)) return;
        lastCombatHitTick.put(player.getUUID(), player.level.getGameTime());
    }

    @SubscribeEvent
    public void onPlayerHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level.isClientSide) return;
        Long hitTick = lastCombatHitTick.get(player.getUUID());
        if (hitTick == null) return;
        if (player.level.getGameTime() - hitTick > COMBAT_WINDOW_TICKS) return;
        event.setAmount(event.getAmount() * COMBAT_HEAL_MULT);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        attackReadyAtTick.remove(id);
        pendingDamageMult.remove(id);
        lastCombatHitTick.remove(id);
        pendingHits.remove(id);
        pendingOffhandHits.remove(id);
        clientOffhandTurn.remove(id);
        serverOffhandTurn.remove(id);
        ComboTracker.INSTANCE.clear(id);
    }

    @SubscribeEvent
    public void onSwingEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Player player = event.getEntity();
        if (!player.level.isClientSide) return;
        if (AnimationController.INSTANCE.isPlaying(player.getUUID())) return;
        triggerAnimation(player, false);
    }

    // -------------------------------------------------------------------------

    public void clientAttack(Player player) {
        if (AnimationController.INSTANCE.isPlaying(player.getUUID())) return;
        UUID pid = player.getUUID();
        if (isDualWielding(player)) {
            clientOffhandTurn.put(pid, !clientOffhandTurn.getOrDefault(pid, false));
        }
        triggerAnimation(player, true);
    }

    private void triggerAnimation(Player player, boolean dualWieldTurn) {
        try {
            UUID pid = player.getUUID();
            ItemStack held = player.getMainHandItem();
            WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);
            if (attrs == null || attrs.attacks.isEmpty()) return;

            int idx = ComboTracker.INSTANCE.pickAttack(pid, attrs, player.tickCount);
            if (idx < 0 || idx >= attrs.attacks.size()) return;
            AttackDefinition attack = attrs.attacks.get(idx);
            if (attack == null || attack.animation == null) return;

            AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attack.animation);
            if (anim == null) {
                BromaxBattle.LOGGER.warn("[BHB] Animation not found: {}", attack.animation);
                return;
            }

            float speed = liveSpeedMultiplier(player) * attack.speedMultiplier * 0.7f;

            if (dualWieldTurn && isDualWielding(player)) {
                if (clientOffhandTurn.getOrDefault(pid, false)) {
                    AnimationController.INSTANCE.playOffhand(pid, anim, speed);
                } else {
                    AnimationController.INSTANCE.play(pid, anim, speed);
                }
            } else {
                AnimationController.INSTANCE.play(pid, anim, speed);
            }
        } catch (Exception e) {
            BromaxBattle.LOGGER.warn("[BHB] triggerAnimation failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private static boolean isDualWielding(Player player) {
        WeaponAttributes mainAttrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
        if (mainAttrs != null && mainAttrs.category.isTwoHanded()) return false;
        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.isEmpty()) return false;
        Item item = offhand.getItem();
        if (item instanceof SwordItem || item instanceof AxeItem) return true;
        if (WeaponRegistry.INSTANCE.getAttributes(offhand) != null) return true;
        var mods = offhand.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE);
        return !mods.isEmpty();
    }

    private static boolean isFocused(Player player) {
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
        if (attrs == null || !attrs.category.isTwoHanded()) return false;
        return player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty();
    }

    private static float liveSpeedMultiplier(Player player) {
        try {
            AttributeInstance attr = player.getAttribute(Attributes.ATTACK_SPEED);
            if (attr == null) return 1.0f;
            float multiplier = (float) (attr.getValue() / 1.6);
            if (!Float.isFinite(multiplier)) return 1.0f;
            return Math.max(0.4f, Math.min(2.5f, multiplier));
        } catch (Exception e) {
            return 1.0f;
        }
    }

    private static long calcLockTicks(Player player) {
        try {
            AttributeInstance attr = player.getAttribute(Attributes.ATTACK_SPEED);
            if (attr == null) return 12L;
            double speed = attr.getValue();
            if (!Double.isFinite(speed) || speed <= 0) return 12L;
            long ticks = (long) Math.ceil(20.0 / speed) - 1L;
            return Math.max(4L, Math.min(50L, ticks));
        } catch (Exception e) {
            return 12L;
        }
    }

    private static float calcOffhandBaseDamage(Player player, ItemStack offhand) {
        float base = 1.0f;
        for (AttributeModifier mod : offhand.getAttributeModifiers(EquipmentSlot.MAINHAND)
                                           .get(Attributes.ATTACK_DAMAGE)) {
            if (mod.getOperation() == AttributeModifier.Operation.ADDITION) {
                base += (float) mod.getAmount();
            }
        }
        MobEffectInstance str = player.getEffect(MobEffects.DAMAGE_BOOST);
        if (str != null) base += 3.0f * (str.getAmplifier() + 1);
        return Math.max(0f, base);
    }

    private static float getEnchantBonus(ItemStack stack, Entity target) {
        if (!(target instanceof LivingEntity living)) return 0f;
        return EnchantmentHelper.getDamageBonus(stack, living.getMobType());
    }
}
