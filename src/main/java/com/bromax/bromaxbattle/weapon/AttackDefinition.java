package com.bromax.bromaxbattle.weapon;

import net.minecraft.util.ResourceLocation;

public class AttackDefinition {
    public final ResourceLocation animation;
    /** Selection probability weight. Higher = more common. Default 100. */
    public final int   weight;
    /** Scales animation playback speed. 0.8 = slower/heavier, 1.2 = faster/lighter. Default 1.0. */
    public final float speedMultiplier;
    /** Scales damage of this specific strike. Default 1.0. */
    public final float damageMultiplier;
    /**
     * Game ticks to wait after the attack event before applying damage.
     * Set to match the animation's hit_window start converted to real ticks:
     *   realTicks = hit_window.start / speed_multiplier
     * Default 0 = damage fires immediately (legacy / instant weapons).
     */
    public final int hitDelay;

    public AttackDefinition(ResourceLocation animation,
                             int weight, float speedMultiplier, float damageMultiplier,
                             int hitDelay) {
        this.animation        = animation;
        this.weight           = Math.max(1, weight);
        this.speedMultiplier  = speedMultiplier  > 0 ? speedMultiplier  : 1.0f;
        this.damageMultiplier = damageMultiplier > 0 ? damageMultiplier : 1.0f;
        this.hitDelay         = Math.max(0, hitDelay);
    }
}
