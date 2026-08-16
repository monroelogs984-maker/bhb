package com.bromax.bromaxbattle.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Weapon ENDING orientation for an animation, set via the top-level "grip"
 * field in the animation JSON. The weapon rotates from the vanilla hold into
 * this pose over the windup (full by hit-window start), holds through the
 * strike, then blends back out.
 *
 * Rotations are composed around the item's own geometry (applied at the item
 * render origin, so the weapon stays in the hand):
 *   BLADE  = the item's long axis (tools run corner-to-corner, diagonal in the quad plane)
 *   EDGE   = in-plane axis perpendicular to the blade
 *   NORMAL = the quad normal
 *
 *  vanilla — no rotation (default)
 *  forward — blade swings out of its plane to point straight ahead
 *  stab    — forward, then spun 90° around its own shaft (door-handle roll)
 *  slash   — blade laid horizontal in-plane, then rolled flat around its shaft
 *  down    — blade rotated in-plane to point downward (chop enders)
 *  up      — blade rotated in-plane to point straight up (rising strike enders)
 */
public enum WeaponGrip {
    VANILLA(null),
    FORWARD(axis(90, EDGE())),
    STAB(axis(90, Z()).mul(axis(90, EDGE()))),
    SLASH(axis(90, X()).mul(axis(-45, NORMAL()))),
    DOWN(axis(135, NORMAL())),
    UP(axis(-45, NORMAL()));

    /** Target rotation, or null for identity. */
    public final Quaternionf rotation;

    WeaponGrip(Quaternionf rotation) {
        this.rotation = rotation;
    }

    private static Vector3f EDGE()   { return new Vector3f(1f, -1f, 0f).normalize(); }
    private static Vector3f NORMAL() { return new Vector3f(0f, 0f, 1f); }
    private static Vector3f X()      { return new Vector3f(1f, 0f, 0f); }
    private static Vector3f Z()      { return new Vector3f(0f, 0f, 1f); }

    private static Quaternionf axis(float deg, Vector3f axis) {
        return new Quaternionf().rotationAxis((float) Math.toRadians(deg), axis.x, axis.y, axis.z);
    }

    public static WeaponGrip fromString(String s) {
        if (s == null) return VANILLA;
        try {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VANILLA;
        }
    }
}
