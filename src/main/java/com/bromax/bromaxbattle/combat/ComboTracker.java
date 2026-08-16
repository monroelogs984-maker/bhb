package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ComboTracker {
    public static final ComboTracker INSTANCE = new ComboTracker();

    private final Map<UUID, Integer> comboIndices    = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastAttackTicks = new ConcurrentHashMap<>();

    public int pickAttack(UUID playerId, WeaponAttributes attrs, int currentTick) {
        List<AttackDefinition> attacks = attrs.attacks;
        if (attacks.isEmpty()) return 0;
        if (attacks.size() == 1) return 0;

        Integer lastTick = lastAttackTicks.get(playerId);
        if (lastTick != null && (currentTick - lastTick) > (int) BromaxBattleConfig.comboResetTicks()) {
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

    public void clear(UUID playerId) {
        comboIndices.remove(playerId);
        lastAttackTicks.remove(playerId);
    }
}
