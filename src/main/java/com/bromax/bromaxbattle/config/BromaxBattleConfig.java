package com.bromax.bromaxbattle.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class BromaxBattleConfig {

    private static boolean enableNameHeuristics  = true;
    private static float   comboResetTicks       = 40f;
    private static float   baseReach             = 3.5f;
    private static boolean enableSpeedDamageBonus = true;

    public static void load(File configFile) {
        Configuration cfg = new Configuration(configFile);
        cfg.load();

        enableNameHeuristics = cfg.getBoolean("enableNameHeuristics", "classifier", enableNameHeuristics,
            "Allow weapon type detection via item name / registry name patterns. Disable if getting false positives.");

        comboResetTicks = cfg.getFloat("comboResetTicks", "combat", comboResetTicks, 10f, 200f,
            "Ticks before the combo resets after the last attack.");
        baseReach = cfg.getFloat("baseReach", "combat", baseReach, 1f, 10f,
            "Base melee reach in blocks. Per-weapon range_bonus is added on top.");
        enableSpeedDamageBonus = cfg.getBoolean("enableSpeedDamageBonus", "combat", enableSpeedDamageBonus,
            "Bonus damage based on movement speed at the moment of attack: "
            + "5% of base weapon damage at sprint speed, 10% at sprint-jump speed, "
            + "15% beyond vanilla max (mounts, elytra, speed effects).");

        if (cfg.hasChanged()) cfg.save();
    }

    public static boolean enableNameHeuristics()  { return enableNameHeuristics; }
    public static float   comboResetTicks()       { return comboResetTicks; }
    public static float   baseReach()             { return baseReach; }
    public static boolean enableSpeedDamageBonus() { return enableSpeedDamageBonus; }
}
