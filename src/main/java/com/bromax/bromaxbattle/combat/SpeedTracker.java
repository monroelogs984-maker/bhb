package com.bromax.bromaxbattle.combat;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player horizontal movement speed (blocks/tick) server-side,
 * smoothed with an exponential moving average so sprint-jump oscillation
 * (fast airborne ticks, slow ground-friction ticks) doesn't make the speed
 * damage bonus tier flicker between attacks.
 *
 * Works from position deltas, so it captures mounts, elytra, and modded
 * movement without caring how the player is moving.
 */
public class SpeedTracker {

    private static final Map<UUID, Float> smoothed = new ConcurrentHashMap<>();

    private static final float EMA_WEIGHT = 0.35f;
    // Single-tick displacement above this is a teleport, not movement (fastest
    // legit travel — boosted elytra — peaks around 1.7 b/t)
    private static final float TELEPORT_CUTOFF = 4.0f;

    public static void tick(Player player) {
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        float sample = (float) Math.sqrt(dx * dx + dz * dz);
        if (!Float.isFinite(sample) || sample > TELEPORT_CUTOFF) return;

        smoothed.merge(player.getUUID(), sample,
                (prev, cur) -> prev + (cur - prev) * EMA_WEIGHT);
    }

    /** Smoothed horizontal speed in blocks/tick, 0 if untracked. */
    public static float getSpeed(UUID playerId) {
        return smoothed.getOrDefault(playerId, 0f);
    }

    public static void clear(UUID playerId) {
        smoothed.remove(playerId);
    }
}
