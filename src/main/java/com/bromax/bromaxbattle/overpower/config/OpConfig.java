package com.bromax.bromaxbattle.overpower.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Tuning for the Overpower system (common config, bromax_battle-common.toml). */
public final class OpConfig {
    private OpConfig() {}

    public static final ModConfigSpec SPEC;

    // Building pressure
    public static final ModConfigSpec.DoubleValue PLAYER_HIT_PRESSURE;
    public static final ModConfigSpec.DoubleValue HEAVY_MULT;
    public static final ModConfigSpec.DoubleValue LIGHT_MULT;
    public static final ModConfigSpec.DoubleValue CRIT_MULT;
    public static final ModConfigSpec.DoubleValue RELIEF_FRACTION;
    public static final ModConfigSpec.DoubleValue MOB_HIT_PRESSURE;
    public static final ModConfigSpec.DoubleValue ELITE_MULT;
    public static final ModConfigSpec.DoubleValue BOSS_MULT;
    public static final ModConfigSpec.DoubleValue ORDINARY_MOB_CAP;
    public static final ModConfigSpec.DoubleValue ELITE_HEALTH;
    public static final ModConfigSpec.DoubleValue BOSS_RESISTANCE;
    // Decay
    public static final ModConfigSpec.IntValue    DECAY_DELAY_TICKS;
    public static final ModConfigSpec.DoubleValue MOB_DECAY_PER_TICK;
    public static final ModConfigSpec.DoubleValue PLAYER_DECAY_PER_TICK;
    public static final ModConfigSpec.DoubleValue STEADY_DRAIN_PER_SECOND;
    // Overpowered
    public static final ModConfigSpec.IntValue    OVERPOWERED_TICKS;
    public static final ModConfigSpec.IntValue    BOSS_STAGGER_TICKS;
    public static final ModConfigSpec.DoubleValue BREAK_KNOCKBACK;
    // Glare Strike
    public static final ModConfigSpec.DoubleValue GLARE_THRESHOLD;
    public static final ModConfigSpec.IntValue    GLARE_WINDOW_TICKS;
    public static final ModConfigSpec.IntValue    GLARE_POSE_TICKS;
    public static final ModConfigSpec.DoubleValue GLARE_DAMAGE;
    public static final ModConfigSpec.DoubleValue GLARE_DAMAGE_TAKEN;
    public static final ModConfigSpec.IntValue    GLARE_STAGGER_TICKS;
    public static final ModConfigSpec.DoubleValue GLARE_FLIP_PRESSURE;
    // Dual wield
    public static final ModConfigSpec.DoubleValue OFFHAND_DAMAGE;
    public static final ModConfigSpec.IntValue    GUARD_COOLDOWN_TICKS;
    public static final ModConfigSpec.DoubleValue GUARD_BLOCKED_PRESSURE;
    // HUD
    public static final ModConfigSpec.IntValue    HUD_LINGER_TICKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Building pressure. A full bar (100) overpowers the entity.").push("pressure");
        PLAYER_HIT_PRESSURE = b.comment("Pressure a player's standard hit adds to the target, before the weapon's Overpower level (x0.5 at -2 .. x1.5 at +2).")
                .defineInRange("playerHitPressure", 14.0, 0.0, 100.0);
        HEAVY_MULT = b.comment("Multiplier for BHB heavy swings (the rare slow, hard-hitting variant).").defineInRange("heavyMultiplier", 1.6, 0.0, 10.0);
        LIGHT_MULT = b.comment("Multiplier for BHB light swings (the rare quick, weaker variant).").defineInRange("lightMultiplier", 0.7, 0.0, 10.0);
        CRIT_MULT = b.comment("Multiplier for BHB crits.").defineInRange("critMultiplier", 1.25, 0.0, 10.0);
        RELIEF_FRACTION = b.comment("Landing a hit also takes this share of the pressure dealt off your own bar.")
                .defineInRange("reliefFraction", 0.35, 0.0, 1.0);
        MOB_HIT_PRESSURE = b.comment("Pressure a mob's hit adds to a player.").defineInRange("mobHitPressure", 12.0, 0.0, 100.0);
        ELITE_MULT = b.comment("Multiplier for elite mobs (max health >= eliteHealth, or tagged bromax_battle:elites).")
                .defineInRange("eliteMultiplier", 1.4, 0.0, 10.0);
        BOSS_MULT = b.comment("Multiplier for bosses (c:bosses).").defineInRange("bossMultiplier", 2.0, 0.0, 10.0);
        ORDINARY_MOB_CAP = b.comment("Ordinary mobs can't push a player past this (only elites and bosses can fully overpower a player).")
                .defineInRange("ordinaryMobCap", 70.0, 0.0, 100.0);
        ELITE_HEALTH = b.comment("Mobs with at least this much max health count as elites.").defineInRange("eliteHealth", 40.0, 1.0, 10000.0);
        BOSS_RESISTANCE = b.comment("Pressure taken by bosses is multiplied by this.").defineInRange("bossResistance", 0.5, 0.0, 1.0);
        b.pop();

        b.push("decay");
        DECAY_DELAY_TICKS = b.comment("Ticks without pressure before the bar starts draining.").defineInRange("delayTicks", 40, 0, 600);
        MOB_DECAY_PER_TICK = b.defineInRange("mobPerTick", 0.6, 0.0, 100.0);
        PLAYER_DECAY_PER_TICK = b.comment("Players drain fast so swarms can't chain-lock them.").defineInRange("playerPerTick", 1.2, 0.0, 100.0);
        STEADY_DRAIN_PER_SECOND = b.comment("Every bar also loses this much per second at all times, even mid-fight, so an advantage fades unless it's kept up.")
                .defineInRange("steadyPerSecond", 2.0, 0.0, 100.0);
        b.pop();

        b.push("overpowered");
        OVERPOWERED_TICKS = b.comment("How long Overpowered (slowness + weakness) lasts after a full bar.").defineInRange("durationTicks", 60, 1, 1200);
        BOSS_STAGGER_TICKS = b.comment("Breaking a boss's bar staggers it instead of slowing it.").defineInRange("bossStaggerTicks", 30, 0, 600);
        BREAK_KNOCKBACK = b.comment("Knockback when a bar breaks (the only knockback Overpower deals).").defineInRange("breakKnockback", 0.8, 0.0, 5.0);
        b.pop();

        b.comment("Glare Strike: while under pressure, taking a hit opens a short window to counter the attacker.").push("glare");
        GLARE_THRESHOLD = b.comment("Pressure needed for a hit to open a Glare window, at Glare level 0 (-2 needs +20, +2 needs -20).")
                .defineInRange("threshold", 45.0, 0.0, 100.0);
        GLARE_WINDOW_TICKS = b.comment("Window length at Glare level 0 (x0.5 at -2 .. x1.75 at +2).").defineInRange("windowTicks", 8, 1, 100);
        GLARE_POSE_TICKS = b.comment("Show-off pose before the thrust.").defineInRange("poseTicks", 10, 1, 60);
        GLARE_DAMAGE = b.comment("The thrust's damage, the same for every weapon (scaled by the glare_damage attribute).")
                .defineInRange("damage", 9.0, 0.0, 1000.0);
        GLARE_DAMAGE_TAKEN = b.comment("Damage the player takes during the pose is multiplied by this.").defineInRange("damageTakenDuringPose", 0.3, 0.0, 1.0);
        GLARE_STAGGER_TICKS = b.defineInRange("staggerTicks", 24, 0, 200);
        GLARE_FLIP_PRESSURE = b.comment("Pressure the thrust adds to the attacker (the player's own bar resets).").defineInRange("flipPressure", 60.0, 0.0, 200.0);
        b.pop();

        b.comment("Dual wielding: right-click attacks with a BHB weapon in the off hand.").push("dualWield");
        OFFHAND_DAMAGE = b.comment("Off-hand hits deal the off-hand weapon's damage times this.").defineInRange("offhandDamage", 0.85, 0.0, 2.0);
        b.pop();

        b.comment("Guard: the guard key (Tab by default) raises a weapon to block one frontal hit.").push("guard");
        GUARD_COOLDOWN_TICKS = b.comment("After blocking a hit the guard drops and can't be raised again for this long.")
                .defineInRange("cooldownTicks", 30, 0, 600);
        GUARD_BLOCKED_PRESSURE = b.comment("A blocked hit still adds this fraction of its normal Overpower pressure to the blocker.")
                .defineInRange("blockedPressure", 0.5, 0.0, 1.0);
        b.pop();

        b.push("hud");
        HUD_LINGER_TICKS = b.comment("Client: ticks the Overpower bar stays after combat before the XP bar returns.").defineInRange("lingerTicks", 80, 0, 1200);
        b.pop();
        SPEC = b.build();
    }

    public static float f(ModConfigSpec.DoubleValue v) { return v.get().floatValue(); }
}
