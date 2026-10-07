package com.bromax.bromaxbattle.weapon;

import net.minecraft.util.ResourceLocation;

public class AttackDefinition {
    public final ResourceLocation animation;
    public final int   weight;
    public final float speedMultiplier;
    public final float damageMultiplier;
    public final int   hitDelay;

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
