package com.bromax.bromaxbattle.overpower.combat;

/**
 * Per-entity Overpower state (server side, not saved: pressure is a combat-only meter).
 * pressure 0..100 is how overpowered this entity is; 100 breaks the bar.
 */
public class OverpowerData {
    public float pressure;
    public long  lastPressureTick;
    /** Glare Strike window (players only): the attacker it's against and when it closes. */
    public int   glareTargetId = -1;
    public long  glareUntilTick;
    public int   glareWindowTicks;
    /** Pending Glare thrust (players only). */
    public int   thrustTargetId = -1;
    public long  thrustAtTick;
    /** HUD: the entity this player is fighting, and when its pressure last changed. */
    public int   hudTargetId = -1;
    public long  lastSyncTick;
}
