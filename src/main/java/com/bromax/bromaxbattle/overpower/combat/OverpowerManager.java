package com.bromax.bromaxbattle.overpower.combat;

import com.bromax.bromaxbattle.api.BhbHitEvent;
import com.bromax.bromaxbattle.overpower.config.OpConfig;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import com.bromax.bromaxbattle.overpower.profile.ProfileRegistry;
import com.bromax.bromaxbattle.overpower.profile.WeaponProfile;
import com.bromax.bromaxbattle.overpower.registry.OpRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Server-side Overpower rules.
 *
 *  - A player's BHB hit adds pressure to the target (weapon Overpower level, heavy/light/crit,
 *    attacker power vs target resistance, bosses resist) and relieves some of the player's own.
 *  - A mob's hit adds pressure to the player; ordinary mobs can't push past a cap, so only elites
 *    and bosses can fully overpower a player.
 *  - At 100 the bar breaks: Overpowered (slowness + weakness) or, for bosses, Exposed + a stagger,
 *    plus the only knockback the system deals; the bar resets.
 *  - Pressure drains after a short pause (players drain fast).
 *  - Glare Strike: a player under enough pressure who takes a hit gets a short window; attacking
 *    that attacker inside it replaces the attack with a Glare Strike: the attacker is staggered,
 *    the player holds a show-off pose taking reduced damage, then a heavy thrust (same damage for
 *    every weapon) that resets the player's bar and loads the attacker's.
 */
public class OverpowerManager {

    public static final TagKey<EntityType<?>> ELITES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("bromax_battle", "elites"));

    // -------------------------------------------------------------------------
    // Building pressure
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onBhbHit(BhbHitEvent event) {
        if (!(event.getTarget() instanceof LivingEntity target) || !target.isAlive()) return;
        Player attacker = event.getAttacker();
        WeaponProfile profile = ProfileRegistry.get(event.getCategory());
        float amount = OpConfig.f(OpConfig.PLAYER_HIT_PRESSURE) * levelMult(profile.overpower());
        if (event.isHeavy()) amount *= OpConfig.f(OpConfig.HEAVY_MULT);
        else if (event.isLight()) amount *= OpConfig.f(OpConfig.LIGHT_MULT);
        if (event.isCrit()) amount *= OpConfig.f(OpConfig.CRIT_MULT);
        amount *= power(attacker);
        float dealt = addPressure(target, amount, attacker);
        relieve(attacker, dealt * OpConfig.f(OpConfig.RELIEF_FRACTION));
        if (attacker instanceof ServerPlayer sp) {
            data(sp).hudTargetId = target.getId();
            OpNetwork.syncHud(sp);
        }
        if (target instanceof ServerPlayer tp) OpNetwork.syncHud(tp);
    }

    /** Mob hits on players; also opens the Glare window and applies the pose's damage reduction. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onIncoming(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        Entity src = event.getSource().getEntity();

        // Staggered entities deal no damage
        if (src instanceof LivingEntity srcLiving && srcLiving.hasEffect(OpRegistries.STAGGERED)) {
            event.setCanceled(true);
            return;
        }
        if (!(victim instanceof ServerPlayer player)) return;

        if (player.hasEffect(OpRegistries.GLARING)) {
            event.setAmount(event.getAmount() * OpConfig.f(OpConfig.GLARE_DAMAGE_TAKEN));
        }
        if (!(src instanceof Mob mob) || event.getSource().getDirectEntity() != mob) return;

        OverpowerData d = data(player);
        // Glare window: needs pressure already on the player before this hit
        WeaponProfile profile = ProfileRegistry.of(player.getMainHandItem());
        int glare = profile != null ? profile.glare() : -2;
        float threshold = OpConfig.f(OpConfig.GLARE_THRESHOLD) - 10f * glare;
        if (profile != null && d.pressure >= threshold && d.thrustTargetId < 0) {
            float window = OpConfig.GLARE_WINDOW_TICKS.get() * windowMult(glare)
                    * (float) player.getAttributeValue(OpRegistries.GLARE_WINDOW);
            d.glareTargetId = mob.getId();
            d.glareWindowTicks = Math.max(1, Math.round(window));
            d.glareUntilTick = player.level().getGameTime() + d.glareWindowTicks;
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.8f);
        }

        float amount = OpConfig.f(OpConfig.MOB_HIT_PRESSURE) * mobClassMult(mob) * power(mob);
        float cap = isEliteOrBoss(mob) ? Float.MAX_VALUE : OpConfig.f(OpConfig.ORDINARY_MOB_CAP);
        addPressure(player, amount, mob, cap);
        d.hudTargetId = mob.getId();
        OpNetwork.syncHud(player);
    }

    // -------------------------------------------------------------------------
    // Glare Strike
    // -------------------------------------------------------------------------

    /** Highest priority so a Glare Strike replaces BHB's normal attack (cancelled events skip BHB). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OverpowerData d = data(player);
        long now = player.level().getGameTime();
        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity living)) return;
        if (tryGlare(player, living, now)) event.setCanceled(true);
    }

    /** Starts a Glare Strike if this player's window is open against this target. */
    public static boolean tryGlare(ServerPlayer player, LivingEntity target, long now) {
        OverpowerData d = data(player);
        if (d.glareTargetId != target.getId() || now > d.glareUntilTick) return false;
        d.glareTargetId = -1;
        startGlare(player, target, now);
        return true;
    }

    private static void startGlare(ServerPlayer player, LivingEntity target, long now) {
        OverpowerData d = data(player);
        int pose = OpConfig.GLARE_POSE_TICKS.get();
        target.addEffect(new MobEffectInstance(OpRegistries.STAGGERED, OpConfig.GLARE_STAGGER_TICKS.get(), 0, false, false, false));
        if (target instanceof Mob mob) mob.getNavigation().stop();
        player.addEffect(new MobEffectInstance(OpRegistries.GLARING, pose + 4, 0, false, false, false));
        d.thrustTargetId = target.getId();
        d.thrustAtTick = now + pose;
        ServerLevel level = player.serverLevel();
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1f, 1.4f);
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, target.getX(), target.getY(0.6), target.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
        OpNetwork.playGlare(player, pose);
        OpNetwork.syncHud(player);
    }

    private void fireThrust(ServerPlayer player, OverpowerData d) {
        Entity e = player.level().getEntity(d.thrustTargetId);
        d.thrustTargetId = -1;
        if (!(e instanceof LivingEntity target) || !target.isAlive() || player.distanceToSqr(target) > 36) return;
        float damage = OpConfig.f(OpConfig.GLARE_DAMAGE) * (float) player.getAttributeValue(OpRegistries.GLARE_DAMAGE);
        target.invulnerableTime = 0;
        target.hurt(player.damageSources().playerAttack(player), damage);
        double yaw = Math.toRadians(player.getYRot());
        target.knockback(0.6, Math.sin(yaw), -Math.cos(yaw));
        // Flip the bar
        d.pressure = 0f;
        addPressure(target, OpConfig.f(OpConfig.GLARE_FLIP_PRESSURE), player);
        ServerLevel level = player.serverLevel();
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1f, 0.8f);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.6), target.getZ(), 16, 0.3, 0.3, 0.3, 0.3);
        d.hudTargetId = target.getId();
        OpNetwork.syncHud(player);
    }

    // -------------------------------------------------------------------------
    // Decay, thrust timing, HUD refresh
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity living) || living.level().isClientSide) return;
        if (!living.hasData(OpRegistries.DATA)) return;
        OverpowerData d = living.getData(OpRegistries.DATA);
        long now = living.level().getGameTime();

        if (living instanceof ServerPlayer player) {
            if (d.thrustTargetId >= 0 && now >= d.thrustAtTick) fireThrust(player, d);
            if (d.glareTargetId >= 0 && now > d.glareUntilTick) {
                d.glareTargetId = -1;
                OpNetwork.syncHud(player);
            }
        }
        if (d.pressure > 0f && now - d.lastPressureTick > OpConfig.DECAY_DELAY_TICKS.get()) {
            float rate = living instanceof Player ? OpConfig.f(OpConfig.PLAYER_DECAY_PER_TICK) : OpConfig.f(OpConfig.MOB_DECAY_PER_TICK);
            d.pressure = Math.max(0f, d.pressure - rate);
        }
        // Players see their own bar and their target's while either is non-zero
        if (living instanceof ServerPlayer player && now - d.lastSyncTick >= 5) {
            Entity t = d.hudTargetId >= 0 ? player.level().getEntity(d.hudTargetId) : null;
            float tp = t instanceof LivingEntity tl && tl.hasData(OpRegistries.DATA) ? tl.getData(OpRegistries.DATA).pressure : 0f;
            if (d.pressure > 0f || tp > 0f || d.glareTargetId >= 0) OpNetwork.syncHud(player);
        }
    }

    // -------------------------------------------------------------------------

    /** Adds pressure (scaled by the target's resistance), breaking the bar at 100. Returns the amount added. */
    public static float addPressure(LivingEntity target, float amount, Entity by) {
        return addPressure(target, amount, by, Float.MAX_VALUE);
    }

    private static float addPressure(LivingEntity target, float amount, Entity by, float cap) {
        OverpowerData d = data(target);
        float resist = (float) Math.max(0.1, target.getAttributeValue(OpRegistries.OVERPOWER_RESISTANCE));
        float add = amount / resist;
        if (isBoss(target)) add *= OpConfig.f(OpConfig.BOSS_RESISTANCE);
        float before = d.pressure;
        d.pressure = Math.min(Math.max(before, cap), before + add);
        d.pressure = Math.min(d.pressure, 100f);
        d.lastPressureTick = target.level().getGameTime();
        if (d.pressure >= 100f) breakBar(target, by);
        return Math.max(0f, d.pressure - before);
    }

    private static void relieve(LivingEntity entity, float amount) {
        if (!entity.hasData(OpRegistries.DATA) || amount <= 0f) return;
        OverpowerData d = entity.getData(OpRegistries.DATA);
        d.pressure = Math.max(0f, d.pressure - amount);
    }

    private static void breakBar(LivingEntity victim, Entity by) {
        OverpowerData d = data(victim);
        d.pressure = 0f;
        int ticks = OpConfig.OVERPOWERED_TICKS.get();
        if (isBoss(victim)) {
            victim.addEffect(new MobEffectInstance(OpRegistries.EXPOSED, ticks, 0, false, false, false));
            victim.addEffect(new MobEffectInstance(OpRegistries.STAGGERED, OpConfig.BOSS_STAGGER_TICKS.get(), 0, false, false, false));
        } else {
            victim.addEffect(new MobEffectInstance(OpRegistries.OVERPOWERED, ticks, 0, false, false, false));
        }
        if (by != null) {
            double dx = by.getX() - victim.getX(), dz = by.getZ() - victim.getZ();
            victim.knockback(OpConfig.f(OpConfig.BREAK_KNOCKBACK), dx, dz);
        }
        if (victim.level() instanceof ServerLevel level) {
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SHIELD_BREAK, SoundSource.HOSTILE, 1f, 0.7f);
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, victim.getX(), victim.getY(0.8), victim.getZ(), 8, 0.3, 0.2, 0.3, 0.1);
        }
    }

    public static OverpowerData data(LivingEntity entity) {
        return entity.getData(OpRegistries.DATA);
    }

    private static float power(LivingEntity entity) {
        return (float) entity.getAttributeValue(OpRegistries.OVERPOWER_POWER);
    }

    /** -2..+2 -> x0.5 .. x1.5 */
    static float levelMult(int level) {
        return 1f + 0.25f * level;
    }

    /** -2..+2 -> x0.5 .. x1.75 */
    static float windowMult(int level) {
        return switch (level) {
            case -2 -> 0.5f;
            case -1 -> 0.75f;
            case 1 -> 1.35f;
            case 2 -> 1.75f;
            default -> 1f;
        };
    }

    private static float mobClassMult(LivingEntity mob) {
        if (isBoss(mob)) return OpConfig.f(OpConfig.BOSS_MULT);
        if (isElite(mob)) return OpConfig.f(OpConfig.ELITE_MULT);
        return 1f;
    }

    public static boolean isBoss(LivingEntity e) {
        return e.getType().is(Tags.EntityTypes.BOSSES);
    }

    public static boolean isElite(LivingEntity e) {
        return e.getType().is(ELITES) || e.getMaxHealth() >= OpConfig.f(OpConfig.ELITE_HEALTH);
    }

    private static boolean isEliteOrBoss(LivingEntity e) {
        return isBoss(e) || isElite(e);
    }
}
