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
    private static Kind offhand = Kind.DEFAULT;

    public static void update(AttackDefinition variant) {
        current = kindOf(variant);
    }

    /** The off-hand attack's variant, for the second cooldown bar. */
    public static void updateOffhand(AttackDefinition variant) {
        offhand = kindOf(variant);
    }

    public static Kind kindOf(AttackDefinition variant) {
        if (variant == null) return Kind.DEFAULT;
        if (variant.speedMultiplier < 0.99f) return Kind.HEAVY;
        if (variant.speedMultiplier > 1.01f) return Kind.LIGHT;
        return Kind.DEFAULT;
    }

    /** Returns an ARGB color for the current variant. */
    public static int getColor() {
        return colorOf(current);
    }

    public static int getOffhandColor() {
        return colorOf(offhand);
    }

    private static int colorOf(Kind kind) {
        return switch (kind) {
            case HEAVY   -> 0xFFFF4444;
            case LIGHT   -> 0xFF44AAFF;
            default      -> 0xFFFFFFFF;
        };
    }

    public static Kind getCurrent() { return current; }
}
