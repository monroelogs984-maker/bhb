package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.overpower.combat.OverpowerManager;
import com.bromax.bromaxbattle.overpower.config.OpConfig;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The guard: the guard key raises the main-hand weapon like a shield. It stops one hit from the
 * front completely, then drops and can't be raised again for a short cooldown. Attacking with
 * either hand lowers it. A blocked hit still adds part of its Overpower pressure to the blocker.
 */
public final class GuardHandler {
    private static final Set<UUID> GUARDING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> READY_AT = new ConcurrentHashMap<>();

    public static boolean isGuarding(Player player) {
        return GUARDING.contains(player.getUUID());
    }

    /** A melee BHB weapon in the main hand. */
    public static boolean canGuard(Player player) {
        if (player.isSpectator() || !player.isAlive()) return false;
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
        return attrs != null && !attrs.category.isRanged();
    }

    public static void toggle(ServerPlayer player) {
        if (isGuarding(player)) {
            lower(player, 0);
            return;
        }
        Long ready = READY_AT.get(player.getUUID());
        if (ready != null && player.level().getGameTime() < ready) return;
        if (!canGuard(player)) return;
        GUARDING.add(player.getUUID());
        OpNetwork.guardState(player, true, 0);
    }

    /** Lowers the guard if it's up; {@code cooldownTicks} > 0 keeps it down that long. */
    public static void lower(ServerPlayer player, int cooldownTicks) {
        if (!GUARDING.remove(player.getUUID())) return;
        if (cooldownTicks > 0) READY_AT.put(player.getUUID(), player.level().getGameTime() + cooldownTicks);
        OpNetwork.guardState(player, false, cooldownTicks);
    }

    /** Vanilla shield rule: the hit's source must be in front of the player (within 90° of where they face). */
    private static boolean fromFront(Player player, DamageSource source) {
        Vec3 src = source.getSourcePosition();
        if (src == null) return false;
        Vec3 view = player.calculateViewVector(0f, player.getYHeadRot());
        Vec3 to = src.vectorTo(player.position());
        to = new Vec3(to.x, 0, to.z).normalize();
        return to.dot(view) < 0;
    }

    /** Before Overpower's own damage handling (LOW), which a cancelled hit then skips. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isGuarding(player)) return;
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_SHIELD) || !fromFront(player, source)) return;

        event.setCanceled(true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1f, 0.8f + player.getRandom().nextFloat() * 0.4f);
        if (source.getDirectEntity() instanceof LivingEntity attacker) {
            // As a shield does: push the attacker back
            attacker.knockback(0.5, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
        }
        OverpowerManager.onBlockedHit(player, source.getEntity());
        lower(player, OpConfig.GUARD_COOLDOWN_TICKS.get());
    }

    /** Switching to something that can't guard drops it. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (GUARDING.isEmpty()) return;
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (GUARDING.contains(p.getUUID()) && !canGuard(p)) lower(p, 0);
        }
    }

    /** Players who start seeing a guarding player need the pose too. */
    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && isGuarding(target)
                && event.getEntity() instanceof ServerPlayer viewer) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer,
                    new OpNetwork.GuardState(target.getId(), true, 0));
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) lower(p, 0);
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) lower(p, 0);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        GUARDING.remove(event.getEntity().getUUID());
        READY_AT.remove(event.getEntity().getUUID());
    }
}
