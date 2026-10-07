package com.bromax.bromaxbattle.overpower.profile;

/**
 * A weapon category's place on the Overpower / Glare Strike spectrum, each -2 (low) to +2 (good).
 * Investing in the minigame costs a little raw damage; low investment gains a little.
 */
public record WeaponProfile(int overpower, int glare) {
    public static final WeaponProfile NEUTRAL = new WeaponProfile(0, 0);

    public WeaponProfile {
        overpower = Math.max(-2, Math.min(2, overpower));
        glare     = Math.max(-2, Math.min(2, glare));
    }

    /** Damage multiplier: 1 - perLevel x (overpower + glare). */
    public float damageMultiplier(float perLevel) {
        return 1f - perLevel * (overpower + glare);
    }
}
