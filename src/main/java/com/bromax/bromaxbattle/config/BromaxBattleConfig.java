package com.bromax.bromaxbattle.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class BromaxBattleConfig {

    /** Allow weapon type detection via item name patterns (last-resort fallback). */
    public static boolean enableNameHeuristics = true;

    /** Ticks without attacking before the combo counter resets. */
    public static float comboResetTicks = 40f;

    /** Base melee reach in blocks before per-weapon range bonus is applied. */
    public static float baseReach = 3.5f;

    public static void load(File configFile) {
        Configuration cfg = new Configuration(configFile);
        cfg.load();

        enableNameHeuristics = cfg.getBoolean("enableNameHeuristics", "classifier", enableNameHeuristics,
            "Allow weapon type detection via item name / registry name patterns. " +
            "Disable if you're getting false positives on non-weapon items.");

        comboResetTicks = cfg.getFloat("comboResetTicks", "combat", comboResetTicks, 10f, 200f,
            "Ticks before the combo resets after the last attack.");

        baseReach = cfg.getFloat("baseReach", "combat", baseReach, 1f, 10f,
            "Base melee reach in blocks. Weapon range_bonus is added on top of this.");

        if (cfg.hasChanged()) cfg.save();
    }
}
