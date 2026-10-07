package com.bromax.bromaxbattle.api;

/**
 * Hooks for addons. Stable across BHB 1.4.x.
 */
public final class BhbApi {
    private BhbApi() {}

    private static volatile boolean externalDualWield = false;

    /**
     * Call once (any side, during mod construction) when an addon provides its own dual wielding.
     * BHB then stops alternating main-hand/off-hand on left click and treats every attack as a
     * main-hand attack.
     */
    public static void setDualWieldHandledExternally(boolean external) {
        externalDualWield = external;
    }

    public static boolean isDualWieldHandledExternally() {
        return externalDualWield;
    }
}
