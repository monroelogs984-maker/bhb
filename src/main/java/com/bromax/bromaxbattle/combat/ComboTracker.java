package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;

import java.util.*;

public class ComboTracker {
    public static final ComboTracker INSTANCE = new ComboTracker();

    /**
     * Picks a variant index for the given player and weapon.
     *
     * Uses tickCount as the primary seed so server and client compute the same
     * result for the same attack — they share the same tickCount for the local
     * player and neither side has accumulated a separate counter to drift.
     */
    public int pickAttack(UUID playerId, WeaponAttributes attrs, int currentTick) {
        List<AttackDefinition> attacks = attrs.attacks;
        if (attacks.isEmpty()) return 0;
        if (attacks.size() == 1) return 0;

        long seed = (long) currentTick * 0x9e3779b97f4a7c15L ^ playerId.getMostSignificantBits();
        int roll = new Random(seed).nextInt(attrs.totalWeight);

        int cumulative = 0;
        for (int i = 0; i < attacks.size(); i++) {
            cumulative += attacks.get(i).weight;
            if (roll < cumulative) return i;
        }
        return attacks.size() - 1;
    }

}
