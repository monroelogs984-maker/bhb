package com.bromax.bromaxbattle.weapon;

import net.minecraft.util.ResourceLocation;

import java.util.Collections;
import java.util.List;

public class WeaponAttributes {
    public final WeaponCategory        category;
    public final List<AttackDefinition> attacks;
    public final ResourceLocation       idleAnimation;
    public final int                    totalWeight;

    public WeaponAttributes(WeaponCategory category, List<AttackDefinition> attacks,
                            ResourceLocation idleAnimation) {
        this.category      = category;
        this.attacks       = Collections.unmodifiableList(attacks);
        this.idleAnimation = idleAnimation;
        int w = 0;
        for (AttackDefinition a : attacks) w += a.weight;
        this.totalWeight = Math.max(1, w);
    }
}
