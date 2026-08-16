package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.weapon.AttackDefinition;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Tracks the most recently rolled attack variant so the cooldown bar can
 * be colored to tell the player what hit type just fired.
 *
 * Updated client-side from triggerAnimation() when the variant is decided.
 * Color persists while the cooldown bar refills.
 *
 *  DEFAULT → white (0xFFFFFF)
 *  HEAVY   → red   (0xFF4444)
 *  LIGHT   → blue  (0x44AAFF)
 */
@OnlyIn(Dist.CLIENT)
public class VariantIndicator {

    public enum Kind { DEFAULT, HEAVY, LIGHT }

    private static Kind current = Kind.DEFAULT;

    public static void update(AttackDefinition variant) {
        if (variant == null) {
            current = Kind.DEFAULT;
            return;
        }
        if (variant.speedMultiplier < 0.99f) {
            current = Kind.HEAVY;
        } else if (variant.speedMultiplier > 1.01f) {
            current = Kind.LIGHT;
        } else {
            current = Kind.DEFAULT;
        }
    }

    /** Returns an ARGB color for the current variant. */
    public static int getColor() {
        return switch (current) {
            case HEAVY   -> 0xFFFF4444;
            case LIGHT   -> 0xFF44AAFF;
            default      -> 0xFFFFFFFF;
        };
    }

    public static Kind getCurrent() { return current; }
}
