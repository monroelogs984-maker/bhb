package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weighted random attack selector with per-player combo memory.
 *
 * Tracks how many consecutive attacks each player has made. The combo count
 * is used as part of the seed so successive attacks in a flurry produce a
 * varied but deterministic sequence, while idling for comboResetTicks resets
 * back to the start of the pattern.
 *
 * Using (comboIndex XOR currentTick) as the seed means:
 *  - The same combo position at different ticks gives different variants (tick variance).
 *  - The same tick at different combo positions gives different variants (combo variance).
 *  - Both client and server call pickAttack with the same inputs and get the same result.
 */
public class ComboTracker {
    public static final ComboTracker INSTANCE = new ComboTracker();

    private final Map<UUID, Integer> comboIndices    = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastAttackTicks = new ConcurrentHashMap<>();

    public int pickAttack(UUID playerId, WeaponAttributes attrs, int currentTick) {
        List<AttackDefinition> attacks = attrs.attacks;
        if (attacks.isEmpty()) return 0;
        if (attacks.size() == 1) return 0;

        // Reset combo index if player has been idle longer than comboResetTicks
        Integer lastTick = lastAttackTicks.get(playerId);
        if (lastTick != null && (currentTick - lastTick) > (int) BromaxBattleConfig.comboResetTicks) {
            comboIndices.remove(playerId);
        }
        lastAttackTicks.put(playerId, currentTick);

        int comboIndex = comboIndices.getOrDefault(playerId, 0);
        comboIndices.put(playerId, comboIndex + 1);

        long seed = ((long) comboIndex ^ (long) currentTick) * 0x9e3779b97f4a7c15L;
        int roll = new Random(seed).nextInt(attrs.totalWeight);

        int cumulative = 0;
        for (int i = 0; i < attacks.size(); i++) {
            cumulative += attacks.get(i).weight;
            if (roll < cumulative) return i;
        }
        return attacks.size() - 1;
    }

    /** Clears all per-player state on disconnect. */
    public void clear(UUID playerId) {
        comboIndices.remove(playerId);
        lastAttackTicks.remove(playerId);
    }
}
