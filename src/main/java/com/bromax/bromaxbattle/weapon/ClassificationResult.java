package com.bromax.bromaxbattle.weapon;

public class ClassificationResult {
    public static final ClassificationResult NONE = new ClassificationResult(WeaponCategory.SWORD, 0f);

    public final WeaponCategory category;
    /** 0.0 = no match, 1.0 = authoritative explicit override. */
    public final float confidence;

    public ClassificationResult(WeaponCategory category, float confidence) {
        this.category = category;
        this.confidence = confidence;
    }
}
