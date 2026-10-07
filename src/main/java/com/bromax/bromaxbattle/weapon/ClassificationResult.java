package com.bromax.bromaxbattle.weapon;

public class ClassificationResult {
    public static final ClassificationResult NONE = new ClassificationResult(WeaponCategory.GAUNTLETS, 0f);

    public final WeaponCategory category;
    public final float           confidence;

    public ClassificationResult(WeaponCategory category, float confidence) {
        this.category   = category;
        this.confidence = confidence;
    }
}
