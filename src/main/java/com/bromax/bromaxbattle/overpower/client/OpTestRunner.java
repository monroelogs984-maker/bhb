package com.bromax.bromaxbattle.overpower.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Unattended /optest run (-Dbhb.optest=true): opens or creates a flat survival world
 * "op_test", runs /optest, then quits the game.
 */
public final class OpTestRunner {
    private static final String WORLD = "op_test";
    private boolean started, sent;
    private int ticks;

    public static void register() {
        if (!Boolean.getBoolean("bhb.optest")) return;
        NeoForge.EVENT_BUS.register(new OpTestRunner());
    }

    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (started || !(event.getNewScreen() instanceof TitleScreen)) return;
        started = true;
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.getLevelSource().levelExists(WORLD)) {
                mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
            } else {
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.NORMAL,
                        true, rules, new WorldDataConfiguration(
                                net.minecraft.world.level.DataPackConfig.DEFAULT, FeatureFlags.DEFAULT_FLAGS));
                mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET)
                                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        new TitleScreen());
            }
        });
    }

    private static void shot(Minecraft mc, String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, "optest_" + name + ".png", mc.getMainRenderTarget(), msg -> {});
    }

    /** Opens the Patchouli guide through its API by reflection (Patchouli is optional). */
    private static void openGuide() {
        try {
            Class<?> api = Class.forName("vazkii.patchouli.api.PatchouliAPI");
            Object inst = api.getMethod("get").invoke(null);
            inst.getClass().getMethod("openBookGUI", net.minecraft.resources.ResourceLocation.class)
                    .invoke(inst, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bromax_battle", "guide"));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ticks++;
        if (!sent && ticks >= 60 && mc.screen == null) {
            sent = true;
            mc.player.connection.sendCommand("optest");
        }
        // Screenshots of the HUD through the fight, then of the guidebook
        int t = ticks - 60;
        if (sent && t > 0 && t <= 300 && t % 15 == 0) shot(mc, "hud_" + t);
        if (sent && t == 330) openGuide();
        if (sent && t == 350) shot(mc, "guide");
        if (sent && ticks >= 60 + 900) mc.stop();
    }
}
