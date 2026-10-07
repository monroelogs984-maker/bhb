package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.AnimationDefinition;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.world.WorldServer;
import net.minecraft.util.DamageSource;
import net.minecraft.potion.PotionEffect;
import net.minecraft.init.MobEffects;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;

import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemStack;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

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
    private static final float BLOCK_DAMAGE_MULT    = 0.25f; // 75% reduction
    private static final int   BLOCK_COOLDOWN_TICKS = 60;    // 3 seconds

    // Speed damage bonus — thresholds in blocks/tick, smoothed by SpeedTracker.
    // Vanilla reference: sprint = 0.281 b/t, sprint-jump ≈ 0.356 b/t.
    private static final float SPEED_TIER_1 = 0.26f; // sprint          → +5%
    private static final float SPEED_TIER_2 = 0.33f; // sprint-jump     → +10%
    private static final float SPEED_TIER_3 = 0.42f; // beyond vanilla  → +15%

    private static final class PendingHit {
        final EntityPlayer   attacker;
        final Entity   primaryTarget;
        final float    combinedMult;
        final float    flatBonus;
        final float    aoeDamage;
        final WeaponCategory category;
        final long     fireAtTick;
        final boolean  wasCrit;

        PendingHit(EntityPlayer attacker, Entity primaryTarget,
                   float combinedMult, float flatBonus, float aoeDamage,
                   WeaponCategory category, long fireAtTick, boolean wasCrit) {
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
        final EntityPlayer   attacker;
        final Entity   target;
        final float    damage;
        final float    aoeDamage;
        final WeaponCategory category;
        final long     fireAtTick;
        final boolean  wasCrit;

        PendingOffhandHit(EntityPlayer attacker, Entity target,
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

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        if (!player.world.isRemote) return;
        UUID pid = player.getUniqueID();
        if (isDualWielding(player)) {
            clientOffhandTurn.put(pid, !clientOffhandTurn.getOrDefault(pid, false));
        }
        triggerAnimation(player, true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onAttackEntityServer(AttackEntityEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player.world.isRemote) return;
        if (player.isDead) return;
        if (event.getTarget() == null) return;

        UUID pid = player.getUniqueID();
        if (pendingHitRefiring.contains(pid)) return;

        long now    = player.world.getTotalWorldTime();
        Long readyAt = attackReadyAtTick.get(pid);
        if (readyAt != null && now < readyAt) {
            event.setCanceled(true);
            return;
        }

        // Attacking cancels any active block
        if (player.isHandActive()) player.stopActiveHand();

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
                        ItemStack offStack = player.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
                        WeaponAttributes offAttrs = WeaponRegistry.INSTANCE.getAttributes(offStack);
                        float offVariantMult  = 1.0f;
                        int   offHitDelay     = 0;
                        WeaponCategory offCat = null;
                        if (offAttrs != null && !offAttrs.attacks.isEmpty()) {
                            int offIdx = ComboTracker.INSTANCE.pickAttack(
                                    pid, offAttrs, player.ticksExisted + 1);
                            AttackDefinition offVariant = offAttrs.attacks.get(offIdx);
                            offVariantMult = offVariant.damageMultiplier;
                            offHitDelay    = offVariant.hitDelay;
                            offCat         = offAttrs.category;
                        }
                        // Offhand attacks roll the same 15% crit as main hand
                        boolean offCrit = player.getRNG().nextFloat() < 0.15f;
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

            WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getHeldItemMainhand());
            int   idx               = 0;
            float variantDamageMult = 1.0f;
            int   hitDelay          = 0;
            if (attrs != null && !attrs.attacks.isEmpty()) {
                idx = ComboTracker.INSTANCE.pickAttack(pid, attrs, player.ticksExisted);
                AttackDefinition variant = attrs.attacks.get(idx);
                variantDamageMult = variant.damageMultiplier;
                hitDelay          = variant.hitDelay;
            }

            float situationMult        = isDualWielding(player) ? DUAL_WIELD_DMG_MULT
                                       : isFocused(player)      ? TWO_HANDED_FOCUS_MULT
                                       : 1.0f;
            float variantSituationMult = variantDamageMult * situationMult;
            // 15% flat crit chance — baked into combinedMult so direct damage uses it
            boolean wasCrit    = attrs != null && player.getRNG().nextFloat() < 0.15f;
            float combinedMult = wasCrit ? variantSituationMult * 1.5f : variantSituationMult;

            // Speed damage bonus — flat add from base weapon damage, deliberately
            // outside combinedMult so variant/crit multipliers don't scale it
            float speedBonus = attrs != null
                             ? calcBaseWeaponDamage(player.getHeldItemMainhand()) * speedBonusPct(player)
                             : 0f;

            if (hitDelay > 0) {
                event.setCanceled(true);
                float aoeDamage = 0f;
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    IAttributeInstance dmgAttr = player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        // AOE targets don't receive the crit multiplier
                        aoeDamage = (float) dmgAttr.getAttributeValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * variantSituationMult;
                    }
                }
                pendingHits.put(pid, new PendingHit(
                        player, event.getTarget(),
                        combinedMult, speedBonus, aoeDamage,
                        attrs != null ? attrs.category : null,
                        now + hitDelay, wasCrit));
            } else {
                if (combinedMult != 1.0f) pendingDamageMult.put(pid, combinedMult);
                if (speedBonus > 0f)      pendingFlatBonus.put(pid, speedBonus);
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    IAttributeInstance dmgAttr = player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        float aoeDamage = (float) dmgAttr.getAttributeValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * variantSituationMult;
                        if (aoeDamage > 0) {
                            List<EntityLivingBase> aoeTargets = AoeCalculator.getTargets(
                                    player, event.getTarget(), attrs.category);
                            for (EntityLivingBase t : aoeTargets) {
                                t.attackEntityFrom(DamageSource.causePlayerDamage(player), aoeDamage);
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
        if (com.bromax.bromaxbattle.config.BromaxBattleConfig.enableSpeedDamageBonus()) {
            for (EntityPlayer p : net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers()) {
                SpeedTracker.tick(p);
            }
        }

        if (!pendingHits.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingHit>> it = pendingHits.entrySet().iterator();
            while (it.hasNext()) {
                PendingHit hit = it.next().getValue();
                if (hit.attacker.world.getTotalWorldTime() < hit.fireAtTick) continue;
                it.remove();
                firePendingHit(hit);
            }
        }

        if (!pendingOffhandHits.isEmpty()) {
            Iterator<Map.Entry<UUID, PendingOffhandHit>> offIt = pendingOffhandHits.entrySet().iterator();
            while (offIt.hasNext()) {
                PendingOffhandHit hit = offIt.next().getValue();
                if (hit.attacker.world.getTotalWorldTime() < hit.fireAtTick) continue;
                offIt.remove();
                fireOffhandHit(hit);
            }
        }
    }

    private void firePendingHit(PendingHit hit) {
        if (hit.attacker.isDead || hit.primaryTarget.isDead) return;
        if (!(hit.attacker.world instanceof WorldServer)) return;
        WorldServer sl = (WorldServer) hit.attacker.world;

        // Direct damage — bypasses vanilla attack strength scale entirely.
        // combinedMult already includes crit (baked in at click time).
        DamageSource dmgSrc = DamageSource.causePlayerDamage(hit.attacker);
        ItemStack held      = hit.attacker.getHeldItemMainhand();
        float baseDmg      = (float) hit.attacker.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
        float enchantBonus = getEnchantBonus(hit.attacker, held, hit.primaryTarget, baseDmg);
        float totalDmg     = (baseDmg + enchantBonus) * hit.combinedMult + hit.flatBonus;

        // Knockback — Knockback enchantment + sprint bonus (mirrors 1.12.2 attackTargetEntityWithCurrentItem)
        if (hit.primaryTarget instanceof EntityLivingBase) {
            EntityLivingBase le = (EntityLivingBase) hit.primaryTarget;
            double kb = EnchantmentHelper.getKnockbackModifier(hit.attacker);
            if (hit.attacker.isSprinting()) kb += 1.0;
            if (kb > 0) {
                float yaw = hit.attacker.rotationYaw * (float)(Math.PI / 180.0);
                le.knockBack(hit.attacker, (float) (kb * 0.5), Math.sin(yaw), -Math.cos(yaw));
            }
        }

        clearOwnInvulnerability(hit.primaryTarget, hit.attacker);
        try {
            hit.primaryTarget.attackEntityFrom(dmgSrc, totalDmg);
        } catch (Exception e) {
            BromaxBattle.LOGGER.warn("[BHB] Pending hit failed: {}", e.getMessage());
        }

        ItemStack weapon = hit.attacker.getHeldItemMainhand();
        if (!weapon.isEmpty()) weapon.damageItem(1, hit.attacker);

        // Reset cooldown bar so the indicator refill plays for the next attack
        hit.attacker.resetCooldown();

        // Post-attack enchantment effects (fire aspect, etc.)
        try {
            // 1.12.2 equivalents of attackTargetEntityWithCurrentItem's enchantment follow-ups
            int fireAspect = EnchantmentHelper.getFireAspectModifier(hit.attacker);
            if (fireAspect > 0) hit.primaryTarget.setFire(fireAspect * 4);
            if (hit.primaryTarget instanceof EntityLivingBase) {
                EnchantmentHelper.applyThornEnchantments((EntityLivingBase) hit.primaryTarget, hit.attacker);
            }
            EnchantmentHelper.applyArthropodEnchantments(hit.attacker, hit.primaryTarget);
        } catch (Exception ignored) {
        }

        // Crit particles + sound — wasCrit is already baked into totalDmg
        if (hit.wasCrit && !hit.primaryTarget.isDead) {
            // crit() on ServerPlayer sends ClientboundAnimatePacket(entity, 4)
            hit.attacker.onCriticalHit(hit.primaryTarget);
            sl.playSound(null, hit.primaryTarget.posX, hit.primaryTarget.posY, hit.primaryTarget.posZ,
                    net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
                    hit.attacker.getSoundCategory(), 1.0f, 1.0f);
        }

        if (hit.aoeDamage > 0 && hit.category != null) {
            try {
                List<EntityLivingBase> aoeTargets = AoeCalculator.getTargets(
                        hit.attacker, hit.primaryTarget, hit.category);
                for (EntityLivingBase t : aoeTargets) {
                    t.attackEntityFrom(DamageSource.causePlayerDamage(hit.attacker), hit.aoeDamage);
                }
            } catch (Exception e) {
                BromaxBattle.LOGGER.warn("[BHB] Pending AOE failed: {}", e.getMessage());
            }
        }
    }

    private void fireOffhandHit(PendingOffhandHit hit) {
        if (hit.attacker.isDead || hit.target.isDead) return;

        try {
            clearOwnInvulnerability(hit.target, hit.attacker);
            hit.target.attackEntityFrom(DamageSource.causePlayerDamage(hit.attacker), hit.damage);
        } catch (Exception e) {
            BromaxBattle.LOGGER.warn("[BHB] Offhand hit failed: {}", e.getMessage());
        }

        if (hit.wasCrit && !hit.target.isDead && hit.attacker.world instanceof WorldServer) {
            hit.attacker.onCriticalHit(hit.target);
            hit.attacker.world.playSound(null, hit.target.posX, hit.target.posY, hit.target.posZ,
                    net.minecraft.init.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
                    hit.attacker.getSoundCategory(), 1.0f, 1.0f);
        }

        ItemStack offWeapon = hit.attacker.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
        if (!offWeapon.isEmpty()) offWeapon.damageItem(1, hit.attacker);

        if (hit.aoeDamage > 0 && hit.category != null) {
            try {
                List<EntityLivingBase> aoeTargets = AoeCalculator.getTargets(
                        hit.attacker, hit.target, hit.category);
                for (EntityLivingBase t : aoeTargets) {
                    t.attackEntityFrom(DamageSource.causePlayerDamage(hit.attacker), hit.aoeDamage);
                }
            } catch (Exception e) {
                BromaxBattle.LOGGER.warn("[BHB] Offhand AOE failed: {}", e.getMessage());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getSource() == null) return;
        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityPlayer)) return;
        EntityPlayer attacker = (EntityPlayer) src;
        if (attacker.world.isRemote) return;
        Float mult = pendingDamageMult.remove(attacker.getUniqueID());
        Float flat = pendingFlatBonus.remove(attacker.getUniqueID());
        if (mult == null && flat == null) return;
        float amount = event.getAmount();
        if (mult != null) amount *= mult;
        if (flat != null) amount += flat;
        event.setAmount(amount);
    }

    @SubscribeEvent
    public void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote) return;
        if (event.getSource() == null) return;
        if (!(event.getSource().getTrueSource() instanceof EntityLivingBase)) return;
        lastCombatHitTick.put(player.getUniqueID(), player.world.getTotalWorldTime());

        // BHB blocking: player holds RMB on a BHB weapon
        if (player.isActiveItemStackBlocking()) {
            ItemStack held = player.getHeldItemMainhand();
            if (WeaponRegistry.INSTANCE.getAttributes(held) != null) {
                event.setAmount(event.getAmount() * BLOCK_DAMAGE_MULT);
                // Item cooldown (synced to client, prevents immediate re-block)
                player.getCooldownTracker().setCooldown(held.getItem(), BLOCK_COOLDOWN_TICKS);
                player.stopActiveHand();
            }
        }
    }

    @SubscribeEvent
    public void onPlayerHeal(LivingHealEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote) return;
        Long hitTick = lastCombatHitTick.get(player.getUniqueID());
        if (hitTick == null) return;
        if (player.world.getTotalWorldTime() - hitTick > COMBAT_WINDOW_TICKS) return;
        event.setAmount(event.getAmount() * COMBAT_HEAL_MULT);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer player = event.player;
        UUID id = player.getUniqueID();
        attackReadyAtTick.remove(id);
        pendingDamageMult.remove(id);
        pendingFlatBonus.remove(id);
        lastCombatHitTick.remove(id);
        SpeedTracker.clear(id);
        pendingHits.remove(id);
        pendingOffhandHits.remove(id);
        clientOffhandTurn.remove(id);
        serverOffhandTurn.remove(id);
        // Release any active block so the item use state doesn't linger
        if (player.isHandActive()) player.stopActiveHand();
    }

    @SubscribeEvent
    public void onSwingEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        EntityPlayer player = event.getEntityPlayer();
        if (!player.world.isRemote) return;
        triggerAnimation(player, false);
    }

    // -------------------------------------------------------------------------

    public void clientAttack(EntityPlayer player) {
        UUID pid = player.getUniqueID();
        if (isDualWielding(player)) {
            clientOffhandTurn.put(pid, !clientOffhandTurn.getOrDefault(pid, false));
        }
        triggerAnimation(player, true);
    }

    private void triggerAnimation(EntityPlayer player, boolean dualWieldTurn) {
        try {
            UUID pid = player.getUniqueID();
            ItemStack held = player.getHeldItemMainhand();
            WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);
            if (attrs == null || attrs.attacks.isEmpty()) return;

            int idx = ComboTracker.INSTANCE.pickAttack(pid, attrs, player.ticksExisted);
            if (idx < 0 || idx >= attrs.attacks.size()) return;
            AttackDefinition attack = attrs.attacks.get(idx);
            if (attack == null || attack.animation == null) return;

            // Tell the cooldown-bar indicator what variant just fired
            com.bromax.bromaxbattle.client.VariantIndicator.update(attack);

            AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attack.animation);
            if (anim == null) {
                BromaxBattle.LOGGER.warn("[BHB] Animation not found: {}", attack.animation);
                return;
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
    }

    // -------------------------------------------------------------------------

    private static boolean isDualWielding(EntityPlayer player) {
        WeaponAttributes mainAttrs = WeaponRegistry.INSTANCE.getAttributes(player.getHeldItemMainhand());
        if (mainAttrs != null && mainAttrs.category.isTwoHanded()) return false;
        ItemStack offhand = player.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND);
        if (offhand.isEmpty()) return false;
        Item item = offhand.getItem();
        if (item instanceof ItemSword || item instanceof ItemAxe) return true;
        if (WeaponRegistry.INSTANCE.getAttributes(offhand) != null) return true;
        return !offhand.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName()).isEmpty();
    }

    private static boolean isFocused(EntityPlayer player) {
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getHeldItemMainhand());
        if (attrs == null || !attrs.category.isTwoHanded()) return false;
        return player.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND).isEmpty();
    }

    private static float liveSpeedMultiplier(EntityPlayer player) {
        try {
            IAttributeInstance attr = player.getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED);
            if (attr == null) return 1.0f;
            float multiplier = (float) (attr.getAttributeValue() / 1.6);
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
    private static void clearOwnInvulnerability(Entity target, EntityPlayer attacker) {
        if (target instanceof EntityLivingBase && ((EntityLivingBase) target).getRevengeTarget() == attacker) {
            target.hurtResistantTime = 0;
        }
    }

    private static long calcLockTicks(EntityPlayer player) {
        try {
            IAttributeInstance attr = player.getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED);
            if (attr == null) return 12L;
            double speed = attr.getAttributeValue();
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

    private static float speedBonusPct(EntityPlayer player) {
        if (!com.bromax.bromaxbattle.config.BromaxBattleConfig.enableSpeedDamageBonus()) return 0f;
        float speed = SpeedTracker.getSpeed(player.getUniqueID());
        if (speed >= SPEED_TIER_3) return 0.15f;
        if (speed >= SPEED_TIER_2) return 0.10f;
        if (speed >= SPEED_TIER_1) return 0.05f;
        return 0f;
    }

    /** The weapon's own flat attack damage (player base 1 + ADD_VALUE modifiers).
     *  Excludes Strength and multiplicative modifiers — the speed bonus scales
     *  off what the weapon is, not what buffs are running. */
    private static float calcBaseWeaponDamage(ItemStack stack) {
        return Math.max(0f, 1.0f + flatDamage(stack));
    }

    private static float calcOffhandBaseDamage(EntityPlayer player, ItemStack offhand) {
        float base = 1.0f + flatDamage(offhand);
        PotionEffect str = player.getActivePotionEffect(MobEffects.STRENGTH);
        if (str != null) base += 3.0f * (str.getAmplifier() + 1);
        return Math.max(0f, base);
    }

    /** Sum of the stack's additive (operation 0) main-hand attack damage modifiers. */
    private static float flatDamage(ItemStack stack) {
        float sum = 0f;
        for (AttributeModifier mod : stack.getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName())) {
            if (mod.getOperation() == 0) sum += (float) mod.getAmount();
        }
        return sum;
    }

    /** Enchantment damage bonus (1.12.2: additive, by the target's creature attribute). */
    private static float getEnchantBonus(EntityPlayer player, ItemStack stack, Entity target, float baseDamage) {
        net.minecraft.entity.EnumCreatureAttribute type = target instanceof EntityLivingBase
                ? ((EntityLivingBase) target).getCreatureAttribute() : net.minecraft.entity.EnumCreatureAttribute.UNDEFINED;
        return EnchantmentHelper.getModifierForCreature(stack, type);
    }
}
