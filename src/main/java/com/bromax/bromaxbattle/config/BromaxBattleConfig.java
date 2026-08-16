package com.bromax.bromaxbattle.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public class BromaxBattleConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public  static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_NAME_HEURISTICS;
    public static final ModConfigSpec.DoubleValue  COMBO_RESET_TICKS;
    public static final ModConfigSpec.DoubleValue  BASE_REACH;
    public static final ModConfigSpec.BooleanValue ENABLE_SPEED_DAMAGE_BONUS;

    static {
        BUILDER.push("classifier");
        ENABLE_NAME_HEURISTICS = BUILDER
            .comment("Allow weapon type detection via item name / registry name patterns. Disable if getting false positives.")
            .define("enableNameHeuristics", true);
        BUILDER.pop();

        BUILDER.push("combat");
        COMBO_RESET_TICKS = BUILDER
            .comment("Ticks before the combo resets after the last attack.")
            .defineInRange("comboResetTicks", 40.0, 10.0, 200.0);
        BASE_REACH = BUILDER
            .comment("Base melee reach in blocks. Per-weapon range_bonus is added on top.")
            .defineInRange("baseReach", 3.5, 1.0, 10.0);
        ENABLE_SPEED_DAMAGE_BONUS = BUILDER
            .comment("Bonus damage based on movement speed at the moment of attack: "
                   + "5% of base weapon damage at sprint speed, 10% at sprint-jump speed, "
                   + "15% beyond vanilla max (mounts, elytra, speed effects).")
            .define("enableSpeedDamageBonus", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "bromax_battle.toml");
    }

    public static boolean enableNameHeuristics()    { return ENABLE_NAME_HEURISTICS.get(); }
    public static float   comboResetTicks()          { return COMBO_RESET_TICKS.get().floatValue(); }
    public static float   baseReach()                { return BASE_REACH.get().floatValue(); }
    public static boolean enableSpeedDamageBonus()   { return ENABLE_SPEED_DAMAGE_BONUS.get(); }
}
