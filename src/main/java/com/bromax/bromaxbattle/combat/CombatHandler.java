package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatHandler {

    public static CombatHandler INSTANCE;

    private final Map<UUID, Long>  attackReadyAtTick = new ConcurrentHashMap<>();
    private final Map<UUID, Long>  lastCombatHitTick = new ConcurrentHashMap<>();
    private final Map<UUID, Float> pendingDamageMult = new ConcurrentHashMap<>();
    private final Map<UUID, Float> pendingFlatBonus  = new ConcurrentHashMap<>();

    private static final long  COMBAT_WINDOW_TICKS   = 160L;
    private static final float COMBAT_HEAL_MULT      = 0.8f;
    private static final float DUAL_WIELD_DMG_MULT   = 0.8f;
    private static final float TWO_HANDED_FOCUS_MULT = 1.15f;

    // Blocking

    // Speed damage bonus — thresholds in blocks/tick, smoothed by SpeedTracker.
    // Vanilla reference: sprint = 0.281 b/t, sprint-jump ≈ 0.356 b/t.
    private static final float SPEED_TIER_1 = 0.26f; // sprint          → +5%
    private static final float SPEED_TIER_2 = 0.33f; // sprint-jump     → +10%
    private static final float SPEED_TIER_3 = 0.42f; // beyond vanilla  → +15%

    private static final class PendingHit {
        final Player   attacker;
        final Entity   primaryTarget;
        final float    combinedMult;
        final float    flatBonus;
        final float    aoeDamage;
        final WeaponCategory category;
        final long     fireAtTick;
        final boolean  wasCrit;
        final AttackDefinition variant;

        PendingHit(Player attacker, Entity primaryTarget,
                   float combinedMult, float flatBonus, float aoeDamage,
                   WeaponCategory category, long fireAtTick, boolean wasCrit, AttackDefinition variant) {
            this.variant        = variant;
            this.attacker       = attacker;
            this.primaryTarget  = primaryTarget;
            this.combinedMult   = combinedMult;
            this.flatBonus      = flatBonus;
            this.aoeDamage      = aoeDamage;
            this.category       = category;
            this.fireAtTick     = fireAtTick;
            this.wasCrit        = wasCrit;
        }
    }

    private static final class PendingOffhandHit {
        final Player   attacker;
        final Entity   target;
        final float    damage;
        final float    aoeDamage;
        final WeaponCategory category;
        final long     fireAtTick;
        final boolean  wasCrit;

        PendingOffhandHit(Player attacker, Entity target,
                          float damage, float aoeDamage,
                          WeaponCategory category, long fireAtTick, boolean wasCrit) {
            this.attacker   = attacker;
            this.target     = target;
            this.damage     = damage;
            this.aoeDamage  = aoeDamage;
            this.category   = category;
            this.fireAtTick = fireAtTick;
            this.wasCrit    = wasCrit;
        }
    }

    private final Map<UUID, PendingHit>        pendingHits        = new ConcurrentHashMap<>();
    private final Map<UUID, PendingOffhandHit> pendingOffhandHits = new ConcurrentHashMap<>();
    private final Set<UUID>                    pendingHitRefiring = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Boolean> clientOffhandTurn = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> serverOffhandTurn = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------

    // The client-side attack animation is triggered by MixinMultiPlayerGameMode -> clientAttack().
    // A client AttackEntityEvent handler used to trigger it as well, so every attack flipped the
    // dual-wield turn twice and the client never alternated hands.

    /** Variant the client chose for its next attack (sent just before the attack packet). */
    private final Map<UUID, Integer> chosenVariant = new ConcurrentHashMap<>();
    private final java.util.Random clientRandom = new java.util.Random();

    public void setChosenVariant(UUID playerId, int idx) {
        chosenVariant.put(playerId, idx);
    }

    public void clearChosenVariant(UUID playerId) {
        chosenVariant.remove(playerId);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onAttackEntityServer(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (player.isDeadOrDying()) return;
        if (event.getTarget() == null) return;

        UUID pid = player.getUUID();
        if (pendingHitRefiring.contains(pid)) return;
        Integer chosen = chosenVariant.remove(pid);

        long now    = player.level().getGameTime();
        Long readyAt = attackReadyAtTick.get(pid);
        if (readyAt != null && now < readyAt) {
            event.setCanceled(true);
            return;
        }

        // Attacking lowers the guard
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) GuardHandler.lower(sp, 0);

        try {
            long lockTicks = calcLockTicks(player);
            attackReadyAtTick.put(pid, now + lockTicks);

            // A new attack must not silently overwrite an unfired pending hit —
            // long-delay heavies outlast the attack lock, and losing them drops
            // both their damage and their crits. Fire the stale hit now instead.
            PendingHit stale = pendingHits.remove(pid);
            if (stale != null) firePendingHit(stale);
            PendingOffhandHit staleOff = pendingOffhandHits.remove(pid);
            if (staleOff != null) fireOffhandHit(staleOff);

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
                        // Offhand attacks roll the same 15% crit as main hand
                        boolean offCrit = player.getRandom().nextFloat() < 0.15f;
                        float offBase   = calcOffhandBaseDamage(player, offStack);
                        float offDamage = (offBase + getEnchantBonus(player, offStack, event.getTarget(), offBase))
                                          * offVariantMult * DUAL_WIELD_DMG_MULT
                                          * (offCrit ? 1.5f : 1.0f)
                                          + calcBaseWeaponDamage(offStack) * speedBonusPct(player);
                        float offAoe    = 0f;
                        if (offCat != null && AoeCalculator.hasAoe(offCat)) {
                            offAoe = offBase * AoeCalculator.getDamageMult(offCat)
                                     * offVariantMult * DUAL_WIELD_DMG_MULT;
                        }
                        pendingOffhandHits.put(pid, new PendingOffhandHit(
                                player, event.getTarget(),
                                offDamage, offAoe, offCat,
                                now + Math.max(offHitDelay, 1), offCrit));
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
            AttackDefinition variant = null;
            if (attrs != null && !attrs.attacks.isEmpty()) {
                idx = chosen != null && chosen >= 0 && chosen < attrs.attacks.size()
                        ? chosen : ComboTracker.INSTANCE.pickAttack(pid, attrs, player.tickCount);
                variant = attrs.attacks.get(idx);
                variantDamageMult = variant.damageMultiplier;
                hitDelay          = variant.hitDelay;
            }

            float situationMult        = isDualWielding(player) ? DUAL_WIELD_DMG_MULT
                                       : isFocused(player)      ? TWO_HANDED_FOCUS_MULT
                                       : 1.0f;
            float variantSituationMult = variantDamageMult * situationMult;
            // 15% flat crit chance — baked into combinedMult so direct damage uses it
            boolean wasCrit    = attrs != null && player.getRandom().nextFloat() < 0.15f;
            float combinedMult = wasCrit ? variantSituationMult * 1.5f : variantSituationMult;

            // Speed damage bonus — flat add from base weapon damage, deliberately
            // outside combinedMult so variant/crit multipliers don't scale it
            float speedBonus = attrs != null
                             ? calcBaseWeaponDamage(player.getMainHandItem()) * speedBonusPct(player)
                             : 0f;

            if (hitDelay > 0) {
                event.setCanceled(true);
                float aoeDamage = 0f;
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    AttributeInstance dmgAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        // AOE targets don't receive the crit multiplier
                        aoeDamage = (float) dmgAttr.getValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * variantSituationMult;
                    }
                }
                pendingHits.put(pid, new PendingHit(
                        player, event.getTarget(),
                        combinedMult, speedBonus, aoeDamage,
                        attrs != null ? attrs.category : null,
                        now + hitDelay, wasCrit, variant));
            } else {
                NeoForge.EVENT_BUS.post(new com.bromax.bromaxbattle.api.BhbHitEvent(
                        player, event.getTarget(), variant, attrs != null ? attrs.category : null, wasCrit));
                if (combinedMult != 1.0f) pendingDamageMult.put(pid, combinedMult);
                if (speedBonus > 0f)      pendingFlatBonus.put(pid, speedBonus);
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    AttributeInstance dmgAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        float aoeDamage = (float) dmgAttr.getValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * variantSituationMult;
                        if (aoeDamage > 0) {
                            List<LivingEntity> aoeTargets = AoeCalculator.getTargets(
                                    player, event.getTarget(), attrs.category);
                            for (LivingEntity t : aoeTargets) {
                                t.hurt(player.damageSources().playerAttack(player), aoeDamage);
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
    public void onServerTick(ServerTickEvent.Post event) {
        if (com.bromax.bromaxbattle.config.BromaxBattleConfig.enableSpeedDamageBonus()) {
            for (Player p : event.getServer().getPlayerList().getPlayers()) {
                SpeedTracker.tick(p);
            }
        }

        if (!pendingHits.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingHit>> it = pendingHits.entrySet().iterator();
            while (it.hasNext()) {
                PendingHit hit = it.next().getValue();
                if (hit.attacker.level().getGameTime() < hit.fireAtTick) continue;
                it.remove();
                firePendingHit(hit);
            }
        }

        if (!pendingOffhandHits.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingOffhandHit>> offIt = pendingOffhandHits.entrySet().iterator();
            while (offIt.hasNext()) {
                PendingOffhandHit hit = offIt.next().getValue();
                if (hit.attacker.level().getGameTime() < hit.fireAtTick) continue;
                offIt.remove();
                fireOffhandHit(hit);
            }
        }
    }

    private void firePendingHit(PendingHit hit) {
        if (hit.attacker.isDeadOrDying() || hit.primaryTarget.isRemoved()) return;
        if (!(hit.attacker.level() instanceof ServerLevel sl)) return;

        // Direct damage — bypasses vanilla attack strength scale entirely.
        // combinedMult already includes crit (baked in at click time).
        DamageSource dmgSrc = hit.attacker.damageSources().playerAttack(hit.attacker);
        ItemStack held      = hit.attacker.getMainHandItem();
        float baseDmg      = (float) hit.attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float enchantBonus = getEnchantBonus(hit.attacker, held, hit.primaryTarget, baseDmg);
        // Item-specific bonus vanilla adds in Player.attack (mace smash, some modded weapons)
        float itemBonus    = held.getItem().getAttackDamageBonus(hit.primaryTarget, baseDmg, dmgSrc);
        float totalDmg     = (baseDmg + itemBonus + enchantBonus) * hit.combinedMult + hit.flatBonus;

        // Knockback — base attribute + sprint bonus (mirrors vanilla)
        if (hit.primaryTarget instanceof LivingEntity le) {
            double kb = hit.attacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            if (hit.attacker.isSprinting()) kb += 1.0;
            if (kb > 0) {
                float yaw = hit.attacker.getYRot() * (float)(Math.PI / 180.0);
                le.knockback(kb * 0.5, Math.sin(yaw), -Math.cos(yaw));
            }
        }

        clearOwnInvulnerability(hit.primaryTarget, hit.attacker);
        boolean landed = false;
        try {
            landed = hit.primaryTarget.hurt(dmgSrc, totalDmg);
        } catch (Exception e) {
            BromaxBattle.LOGGER.warn("[BHB] Pending hit failed: {}", e.getMessage());
        }
        if (landed) {
            NeoForge.EVENT_BUS.post(new com.bromax.bromaxbattle.api.BhbHitEvent(
                    hit.attacker, hit.primaryTarget, hit.variant, hit.category, hit.wasCrit));
        }

        ItemStack weapon = hit.attacker.getMainHandItem();
        if (!weapon.isEmpty()) weapon.hurtAndBreak(1, hit.attacker, EquipmentSlot.MAINHAND);

        // Reset cooldown bar so the indicator refill plays for the next attack
        hit.attacker.resetAttackStrengthTicker();

        // Post-attack enchantment effects (fire aspect, etc.)
        try {
            EnchantmentHelper.doPostAttackEffects(sl, hit.primaryTarget, dmgSrc);
        } catch (Exception ignored) {
        }

        // Crit particles + sound — wasCrit is already baked into totalDmg
        if (hit.wasCrit && !hit.primaryTarget.isRemoved()) {
            // crit() on ServerPlayer sends ClientboundAnimatePacket(entity, 4)
            hit.attacker.crit(hit.primaryTarget);
            sl.playSound(null, hit.primaryTarget.getX(), hit.primaryTarget.getY(),
                    hit.primaryTarget.getZ(),
                    net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT,
                    hit.attacker.getSoundSource(), 1.0f, 1.0f);
        }

        if (hit.aoeDamage > 0 && hit.category != null) {
            try {
                List<LivingEntity> aoeTargets = AoeCalculator.getTargets(
                        hit.attacker, hit.primaryTarget, hit.category);
                for (LivingEntity t : aoeTargets) {
                    t.hurt(hit.attacker.damageSources().playerAttack(hit.attacker), hit.aoeDamage);
                }
            } catch (Exception e) {
                BromaxBattle.LOGGER.warn("[BHB] Pending AOE failed: {}", e.getMessage());
            }
        }
    }

    private void fireOffhandHit(PendingOffhandHit hit) {
        if (hit.attacker.isDeadOrDying() || hit.target.isRemoved()) return;

        try {
            clearOwnInvulnerability(hit.target, hit.attacker);
            hit.target.hurt(hit.attacker.damageSources().playerAttack(hit.attacker), hit.damage);
        } catch (Exception e) {
            BromaxBattle.LOGGER.warn("[BHB] Offhand hit failed: {}", e.getMessage());
        }

        if (hit.wasCrit && !hit.target.isRemoved() && hit.attacker.level() instanceof ServerLevel sl) {
            hit.attacker.crit(hit.target);
            sl.playSound(null, hit.target.getX(), hit.target.getY(), hit.target.getZ(),
                    net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT,
                    hit.attacker.getSoundSource(), 1.0f, 1.0f);
        }

        ItemStack offWeapon = hit.attacker.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offWeapon.isEmpty()) offWeapon.hurtAndBreak(1, hit.attacker, EquipmentSlot.OFFHAND);

        if (hit.aoeDamage > 0 && hit.category != null) {
            try {
                List<LivingEntity> aoeTargets = AoeCalculator.getTargets(
                        hit.attacker, hit.target, hit.category);
                for (LivingEntity t : aoeTargets) {
                    t.hurt(hit.attacker.damageSources().playerAttack(hit.attacker), hit.aoeDamage);
                }
            } catch (Exception e) {
                BromaxBattle.LOGGER.warn("[BHB] Offhand AOE failed: {}", e.getMessage());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onLivingHurt(LivingIncomingDamageEvent event) {
        if (event.getSource() == null) return;
        Entity src = event.getSource().getEntity();
        if (!(src instanceof Player attacker)) return;
        if (attacker.level().isClientSide) return;
        Float mult = pendingDamageMult.remove(attacker.getUUID());
        Float flat = pendingFlatBonus.remove(attacker.getUUID());
        if (mult == null && flat == null) return;
        float amount = event.getAmount();
        if (mult != null) amount *= mult;
        if (flat != null) amount += flat;
        event.setAmount(amount);
    }

    @SubscribeEvent
    public void onPlayerHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (event.getSource() == null) return;
        if (!(event.getSource().getEntity() instanceof LivingEntity)) return;
        lastCombatHitTick.put(player.getUUID(), player.level().getGameTime());
    }

    @SubscribeEvent
    public void onPlayerHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        Long hitTick = lastCombatHitTick.get(player.getUUID());
        if (hitTick == null) return;
        if (player.level().getGameTime() - hitTick > COMBAT_WINDOW_TICKS) return;
        event.setAmount(event.getAmount() * COMBAT_HEAL_MULT);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUUID();
        attackReadyAtTick.remove(id);
        pendingDamageMult.remove(id);
        pendingFlatBonus.remove(id);
        lastCombatHitTick.remove(id);
        SpeedTracker.clear(id);
        pendingHits.remove(id);
        pendingOffhandHits.remove(id);
        clientOffhandTurn.remove(id);
        serverOffhandTurn.remove(id);
        chosenVariant.remove(id);
    }

    @SubscribeEvent
    public void onSwingEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) return;
        triggerAnimation(player, false);
    }

    // -------------------------------------------------------------------------

    public void clientAttack(Player player) {
        UUID pid = player.getUUID();
        if (isDualWielding(player)) {
            clientOffhandTurn.put(pid, !clientOffhandTurn.getOrDefault(pid, false));
        }
        int idx = triggerAnimation(player, true);
        // The server applies this variant's damage, so the hit always matches the swing shown
        if (idx >= 0) net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bromax.bromaxbattle.overpower.network.OpNetwork.AttackVariant(idx));
    }

    /** Picks this swing's variant and plays it; returns the variant index, or -1 for no BHB weapon. */
    private int triggerAnimation(Player player, boolean dualWieldTurn) {
        int idx = -1;
        try {
            UUID pid = player.getUUID();
            ItemStack held = player.getMainHandItem();
            WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);
            if (attrs == null || attrs.attacks.isEmpty()) return -1;

            idx = ComboTracker.INSTANCE.pickAttackRandom(attrs, clientRandom);
            AttackDefinition attack = attrs.attacks.get(idx);
            if (attack == null || attack.animation == null) return idx;

            // Tell the cooldown-bar indicator what variant just fired
            com.bromax.bromaxbattle.client.VariantIndicator.update(attack);

            AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attack.animation);
            if (anim == null) {
                BromaxBattle.LOGGER.warn("[BHB] Animation not found: {}", attack.animation);
                return idx;
            }

            float speed = liveSpeedMultiplier(player) * attack.speedMultiplier;

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
        return idx;
    }

    // -------------------------------------------------------------------------

    private static boolean isDualWielding(Player player) {
        if (com.bromax.bromaxbattle.api.BhbApi.isDualWieldHandledExternally()) return false;
        WeaponAttributes mainAttrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
        if (mainAttrs != null && mainAttrs.category.isTwoHanded()) return false;
        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.isEmpty()) return false;
        Item item = offhand.getItem();
        if (item instanceof SwordItem || item instanceof AxeItem) return true;
        if (WeaponRegistry.INSTANCE.getAttributes(offhand) != null) return true;
        boolean[] hasDamage = {false};
        offhand.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (attr.equals(Attributes.ATTACK_DAMAGE)) hasDamage[0] = true;
        });
        return hasDamage[0];
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

    /**
     * Delayed hits land hit_delay ticks after the click, and the delay varies by variant, so two
     * hits paced by the attack lock can still arrive under 10 ticks apart (heavy then light).
     * Vanilla's hurt() then keeps only the damage above the previous hit, often nothing. The
     * attack lock already rate-limits BHB, so drop the i-frames this player's own last hit left;
     * damage from anyone else keeps its normal protection.
     */
    private static void clearOwnInvulnerability(Entity target, Player attacker) {
        if (target instanceof LivingEntity le && le.getLastHurtByMob() == attacker) {
            le.invulnerableTime = 0;
        }
    }

    private static long calcLockTicks(Player player) {
        try {
            AttributeInstance attr = player.getAttribute(Attributes.ATTACK_SPEED);
            if (attr == null) return 12L;
            double speed = attr.getValue();
            if (!Double.isFinite(speed) || speed <= 0) return 12L;
            // Vanilla deals full damage once getAttackStrengthScale(0.5) reaches 1,
            // i.e. after ceil(period - 0.5) ticks. Plain ceil(period) was a tick
            // longer for non-integer periods (sword 1.6: 13 vs 12), costing ~8% DPS
            // and refusing clicks made on a full cooldown bar.
            // (epsilon: attribute sums like 4.0 - 2.4 land a hair under 1.6, which
            // would otherwise push 12.0 up to 13)
            long ticks = (long) Math.ceil(20.0 / speed - 0.5 - 1e-6);
            return Math.max(4L, Math.min(50L, ticks));
        } catch (Exception e) {
            return 12L;
        }
    }

    private static float speedBonusPct(Player player) {
        if (!com.bromax.bromaxbattle.config.BromaxBattleConfig.enableSpeedDamageBonus()) return 0f;
        float speed = SpeedTracker.getSpeed(player.getUUID());
        if (speed >= SPEED_TIER_3) return 0.15f;
        if (speed >= SPEED_TIER_2) return 0.10f;
        if (speed >= SPEED_TIER_1) return 0.05f;
        return 0f;
    }

    /** The weapon's own flat attack damage (player base 1 + ADD_VALUE modifiers).
     *  Excludes Strength and multiplicative modifiers — the speed bonus scales
     *  off what the weapon is, not what buffs are running. */
    private static float calcBaseWeaponDamage(ItemStack stack) {
        float[] base = {1.0f};
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (attr.equals(Attributes.ATTACK_DAMAGE)
                    && mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
                base[0] += (float) mod.amount();
            }
        });
        return Math.max(0f, base[0]);
    }

    private static float calcOffhandBaseDamage(Player player, ItemStack offhand) {
        float[] base = {1.0f};
        offhand.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (attr.equals(Attributes.ATTACK_DAMAGE)
                    && mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
                base[0] += (float) mod.amount();
            }
        });
        MobEffectInstance str = player.getEffect(MobEffects.DAMAGE_BOOST);
        if (str != null) base[0] += 3.0f * (str.getAmplifier() + 1);
        return Math.max(0f, base[0]);
    }

    /**
     * 1.21 removed MobType-based EnchantmentHelper.getDamageBonus; the data-driven
     * replacement is modifyDamage, which adds all enchantment damage bonuses to a
     * base value. Passing 0 yields just the bonus.
     */
    /**
     * Enchantment damage on top of {@code baseDamage}, computed like vanilla's getEnchantedDamage:
     * effects run against the real base, so multiplying enchantments contribute (against a base
     * of 0 they added nothing).
     */
    private static float getEnchantBonus(Player player, ItemStack stack, Entity target, float baseDamage) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return 0f;
        return EnchantmentHelper.modifyDamage(serverLevel, stack, target,
                player.damageSources().playerAttack(player), baseDamage) - baseDamage;
    }
}
