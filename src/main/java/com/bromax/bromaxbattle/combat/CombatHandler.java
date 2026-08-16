package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.AnimationDefinition;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import com.bromax.bromaxlib.accessor.IEntityPlayerAttack;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatHandler {

    private final Map<UUID, Long>  attackReadyAtTick = new ConcurrentHashMap<>();
    private final Map<UUID, Long>  lastCombatHitTick = new ConcurrentHashMap<>();
    private final Map<UUID, Float> pendingDamageMult = new ConcurrentHashMap<>();

    private static final long  COMBAT_WINDOW_TICKS   = 160L;
    private static final float COMBAT_HEAL_MULT      = 0.8f;
    private static final float DUAL_WIELD_DMG_MULT   = 0.8f;
    private static final float TWO_HANDED_FOCUS_MULT = 1.15f;

    // -------------------------------------------------------------------------
    // Pending hit system
    //
    // When a weapon's attack definition has hitDelay > 0, the vanilla attack
    // event is cancelled immediately and the hit is stored here. At fireAtTick,
    // the full vanilla attack is re-fired (preserving enchantments, crits, and
    // knockback) so the damage lands when the sword visually reaches the enemy.
    // -------------------------------------------------------------------------

    private static final class PendingHit {
        final EntityPlayer attacker;
        final Entity       primaryTarget;
        final float        combinedMult;  // variant × situation — applied via pendingDamageMult at re-fire
        final float        aoeDamage;
        final WeaponCategory category;   // nullable for unclassified weapons
        final long         fireAtTick;

        PendingHit(EntityPlayer attacker, Entity primaryTarget,
                   float combinedMult, float aoeDamage,
                   WeaponCategory category, long fireAtTick) {
            this.attacker      = attacker;
            this.primaryTarget = primaryTarget;
            this.combinedMult  = combinedMult;
            this.aoeDamage     = aoeDamage;
            this.category      = category;
            this.fireAtTick    = fireAtTick;
        }
    }

    // Offhand hits carry pre-calculated damage — no vanilla re-fire since
    // attackTargetEntityWithCurrentItem only uses the mainhand slot.
    private static final class PendingOffhandHit {
        final EntityPlayer   attacker;
        final Entity         target;
        final float          damage;
        final float          aoeDamage;
        final WeaponCategory category;
        final long           fireAtTick;

        PendingOffhandHit(EntityPlayer attacker, Entity target,
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
    /** Guard: prevents onAttackEntityServer from re-scheduling hits that we're re-firing. */
    private final Set<UUID>                    pendingHitRefiring = ConcurrentHashMap.newKeySet();

    // Dual-wield turn state. false = mainhand's turn, true = offhand's turn.
    // Advanced only on entity attacks, never on air swings, so client and server stay in sync.
    private final Map<UUID, Boolean> clientOffhandTurn = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> serverOffhandTurn = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        if (!player.world.isRemote) return;
        if (AnimationController.INSTANCE.isPlaying(player.getUniqueID())) return;
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

        // Re-fired from pending hit — vanilla handles damage, we just need to
        // allow it through. The damage mult was already placed in pendingDamageMult.
        if (pendingHitRefiring.contains(pid)) return;

        long now = player.world.getTotalWorldTime();
        Long readyAt = attackReadyAtTick.get(pid);
        if (readyAt != null && now < readyAt) {
            event.setCanceled(true);
            return;
        }

        try {
            long lockTicks = calcLockTicks(player);
            attackReadyAtTick.put(pid, now + lockTicks);

            // --- DUAL WIELD TURN CHECK ---
            // Toggle turn first; if it's now the offhand's swing, cancel the vanilla
            // mainhand attack entirely and deal offhand damage instead.
            if (isDualWielding(player)) {
                boolean wasOffhand = serverOffhandTurn.getOrDefault(pid, false);
                serverOffhandTurn.put(pid, !wasOffhand);

                if (wasOffhand) {
                    event.setCanceled(true);
                    try {
                        ItemStack offStack = player.getHeldItemOffhand();
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
                    return; // mainhand does nothing this swing
                }
                // else: mainhand's turn — fall through to normal mainhand processing
            }

            // --- MAINHAND ---
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

            float situationMult = isDualWielding(player) ? DUAL_WIELD_DMG_MULT
                                : isFocused(player)      ? TWO_HANDED_FOCUS_MULT
                                : 1.0f;
            float combinedMult = variantDamageMult * situationMult;

            if (hitDelay > 0) {
                event.setCanceled(true);
                float aoeDamage = 0f;
                if (attrs != null && AoeCalculator.hasAoe(attrs.category)) {
                    IAttributeInstance dmgAttr =
                            player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        aoeDamage = (float) dmgAttr.getAttributeValue()
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
                    IAttributeInstance dmgAttr =
                            player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE);
                    if (dmgAttr != null) {
                        float aoeDamage = (float) dmgAttr.getAttributeValue()
                                * AoeCalculator.getDamageMult(attrs.category)
                                * combinedMult;
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
            BromaxBattle.LOGGER.warn("[BromaxBattle] Attack processing failed: {}", e.getMessage());
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
                if (hit.attacker.world.getTotalWorldTime() < hit.fireAtTick) continue;
                it.remove();

                if (hit.attacker.isDead || hit.primaryTarget.isDead) continue;

                UUID pid = hit.attacker.getUniqueID();

                // Place the damage mult before re-firing so onLivingHurt applies it.
                if (hit.combinedMult != 1.0f) {
                    pendingDamageMult.put(pid, hit.combinedMult);
                }

                // Re-fire the full vanilla attack (enchantments, crits, knockback preserved).
                pendingHitRefiring.add(pid);
                try {
                    ((IEntityPlayerAttack) hit.attacker)
                            .bhb_attackTargetEntityWithCurrentItem(hit.primaryTarget);
                } catch (Exception e) {
                    BromaxBattle.LOGGER.warn("[BHB] Pending hit re-fire failed: {}", e.getMessage());
                    pendingDamageMult.remove(pid);
                } finally {
                    pendingHitRefiring.remove(pid);
                }

                // AOE re-detected at hit time so it uses the player's current position/yaw.
                if (hit.aoeDamage > 0 && hit.category != null) {
                    try {
                        List<EntityLivingBase> aoeTargets = AoeCalculator.getTargets(
                                hit.attacker, hit.primaryTarget, hit.category);
                        for (EntityLivingBase t : aoeTargets) {
                            t.attackEntityFrom(
                                    DamageSource.causePlayerDamage(hit.attacker), hit.aoeDamage);
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
                if (hit.attacker.world.getTotalWorldTime() < hit.fireAtTick) continue;
                offIt.remove();

                if (hit.attacker.isDead || hit.target.isDead) continue;

                try {
                    hit.target.attackEntityFrom(
                            DamageSource.causePlayerDamage(hit.attacker), hit.damage);
                } catch (Exception e) {
                    BromaxBattle.LOGGER.warn("[BHB] Offhand hit failed: {}", e.getMessage());
                }

                if (hit.aoeDamage > 0 && hit.category != null) {
                    try {
                        List<EntityLivingBase> aoeTargets = AoeCalculator.getTargets(
                                hit.attacker, hit.target, hit.category);
                        for (EntityLivingBase t : aoeTargets) {
                            t.attackEntityFrom(
                                    DamageSource.causePlayerDamage(hit.attacker), hit.aoeDamage);
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
        net.minecraft.entity.Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityPlayer)) return;
        EntityPlayer attacker = (EntityPlayer) src;
        if (attacker.world.isRemote) return;
        Float mult = pendingDamageMult.remove(attacker.getUniqueID());
        if (mult != null && mult != 1.0f) {
            event.setAmount(event.getAmount() * mult);
        }
    }

    @SubscribeEvent
    public void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.getEntity();
        if (player.world.isRemote) return;
        if (event.getSource() == null) return;
        if (!(event.getSource().getTrueSource() instanceof EntityLivingBase)) return;
        lastCombatHitTick.put(player.getUniqueID(), player.world.getTotalWorldTime());
    }

    @SubscribeEvent
    public void onPlayerHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.getEntity();
        if (player.world.isRemote) return;
        Long hitTick = lastCombatHitTick.get(player.getUniqueID());
        if (hitTick == null) return;
        if (player.world.getTotalWorldTime() - hitTick > COMBAT_WINDOW_TICKS) return;
        event.setAmount(event.getAmount() * COMBAT_HEAL_MULT);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.player.getUniqueID();
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
        EntityPlayer player = event.getEntityPlayer();
        if (!player.world.isRemote) return;
        if (AnimationController.INSTANCE.isPlaying(player.getUniqueID())) return;
        triggerAnimation(player, false);
    }

    // -------------------------------------------------------------------------
    // Animation trigger
    // -------------------------------------------------------------------------

    // dualWieldTurn=true  → entity attack, check turn map to decide which arm animates
    // dualWieldTurn=false → air swing, always play mainhand animation
    private void triggerAnimation(EntityPlayer player, boolean dualWieldTurn) {
        try {
            UUID pid = player.getUniqueID();
            ItemStack held = player.getHeldItemMainhand();
            WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);
            if (attrs == null || attrs.attacks.isEmpty()) {
                BromaxBattle.LOGGER.warn("[BHB] triggerAnimation: no attrs for {}",
                        held.getItem().getRegistryName());
                return;
            }

            int idx = ComboTracker.INSTANCE.pickAttack(pid, attrs, player.ticksExisted);
            if (idx < 0 || idx >= attrs.attacks.size()) return;
            AttackDefinition attack = attrs.attacks.get(idx);
            if (attack == null || attack.animation == null) return;

            AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attack.animation);
            if (anim == null) {
                BromaxBattle.LOGGER.warn("[BHB] Animation file not found: {}", attack.animation);
                return;
            }

            float speed = liveSpeedMultiplier(player) * attack.speedMultiplier;
            BromaxBattle.LOGGER.info("[BHB] Playing {} cat={} variant={}", attack.animation, attrs.category, idx);

            if (dualWieldTurn && isDualWielding(player)) {
                // Alternate arms. Both hands use the mainhand's animation — offhand mirrors it
                // so the same motion plays on the left arm without needing separate animation files.
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
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isDualWielding(EntityPlayer player) {
        WeaponAttributes mainAttrs = WeaponRegistry.INSTANCE.getAttributes(player.getHeldItemMainhand());
        if (mainAttrs != null && mainAttrs.category.isTwoHanded()) return false;
        ItemStack offhand = player.getHeldItemOffhand();
        if (offhand.isEmpty()) return false;
        Item item = offhand.getItem();
        if (item instanceof ItemSword || item instanceof ItemAxe) return true;
        if (WeaponRegistry.INSTANCE.getAttributes(offhand) != null) return true;
        java.util.Collection<AttributeModifier> mods = offhand
                .getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName());
        return !mods.isEmpty();
    }

    private static boolean isFocused(EntityPlayer player) {
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getHeldItemMainhand());
        if (attrs == null || !attrs.category.isTwoHanded()) return false;
        return player.getHeldItemOffhand().isEmpty();
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

    private static long calcLockTicks(EntityPlayer player) {
        try {
            IAttributeInstance attr = player.getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED);
            if (attr == null) return 12L;
            double speed = attr.getAttributeValue();
            if (!Double.isFinite(speed) || speed <= 0) return 12L;
            long ticks = (long)Math.ceil(20.0 / speed) - 1L;
            return Math.max(4L, Math.min(50L, ticks));
        } catch (Exception e) {
            return 12L;
        }
    }

    // Base offhand damage: item attack attribute (ADD modifiers only) + Strength potion.
    // Does not include per-target enchantment bonuses — call getEnchantBonus() for those.
    private static float calcOffhandBaseDamage(EntityPlayer player, ItemStack offhand) {
        float base = 1.0f;
        java.util.Collection<AttributeModifier> mods = offhand
                .getAttributeModifiers(EntityEquipmentSlot.MAINHAND)
                .get(SharedMonsterAttributes.ATTACK_DAMAGE.getName());
        for (AttributeModifier mod : mods) {
            if (mod.getOperation() == 0) base += (float) mod.getAmount();
        }
        PotionEffect str = player.getActivePotionEffect(MobEffects.STRENGTH);
        if (str != null) base += 3.0f * (str.getAmplifier() + 1);
        return Math.max(0f, base);
    }

    // Sharpness / Smite / Bane of Arthropods bonus for a specific target.
    // Returns 0 for non-living targets or if no relevant enchantments are present.
    private static float getEnchantBonus(ItemStack stack, Entity target) {
        if (!(target instanceof EntityLivingBase)) return 0f;
        return EnchantmentHelper.getModifierForCreature(
                stack, ((EntityLivingBase) target).getCreatureAttribute());
    }
}
