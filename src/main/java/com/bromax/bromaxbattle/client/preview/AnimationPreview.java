package com.bromax.bromaxbattle.client.preview;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Animation preview tool.
 *
 * In game: {@code /bhbpreview [filter]} renders a contact sheet for every animation whose id
 * contains the filter (all of them when omitted) into screenshots/bhb_preview/.
 * {@code /bhbgrips <filter>} renders each animation's strike pose with every grip preset side by side.
 *
 * Unattended: launch the dev client with {@code -Dbhb.preview=<filter|all>} and it opens or
 * creates a flat world called "bhb_preview", renders the sheets, then quits the game.
 */
@OnlyIn(Dist.CLIENT)
public final class AnimationPreview {
    private static final String WORLD = "bhb_preview";
    private static final String AUTO_FILTER = System.getProperty("bhb.preview", "");
    private static final boolean AUTO_GRIPS = Boolean.getBoolean("bhb.preview.grips");
    /** Unattended damage test: "<attacks> [item]", runs /bhbdmgtest in the preview world then quits. */
    private static final String AUTO_DMGTEST = System.getProperty("bhb.dmgtest", "");
    private int quitAtTick = -1;

    private boolean autoStarted = false;
    private boolean autoOpened = false;
    private int ticksInWorld = 0;

    public static void register() {
        NeoForge.EVENT_BUS.register(new AnimationPreview());
    }

    private static boolean autoMode() {
        return !AUTO_FILTER.isEmpty() || !AUTO_DMGTEST.isEmpty();
    }

    public static List<ResourceLocation> select(String filter) {
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        boolean all = f.isEmpty() || f.equals("all");
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : WeaponRegistry.INSTANCE.getAllAnimationIds()) {
            if (AnimationRegistry.INSTANCE.get(id) == null) continue;
            if (all || id.toString().contains(f)) out.add(id);
        }
        out.sort((a, b) -> a.toString().compareTo(b.toString()));
        return out;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bhbgrips")
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(ctx -> open(StringArgumentType.getString(ctx, "filter"), true))));
        event.getDispatcher().register(Commands.literal("bhbpreview")
                .executes(ctx -> open(""))
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(ctx -> open(StringArgumentType.getString(ctx, "filter")))));
    }

    private static int open(String filter) {
        return open(filter, false);
    }

    private static int open(String filter, boolean grips) {
        Minecraft mc = Minecraft.getInstance();
        List<ResourceLocation> jobs = select(filter);
        if (jobs.isEmpty()) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[BHB] No animations match '" + filter + "'"), false);
            return 0;
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[BHB] Rendering " + jobs.size() + " preview sheets to screenshots/bhb_preview"), false);
        }
        // Let the chat screen close first
        mc.tell(() -> mc.setScreen(new AnimationPreviewScreen(jobs, null, grips)));
        return jobs.size();
    }

    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        if (!autoMode() || autoStarted || !(event.getNewScreen() instanceof TitleScreen)) return;
        autoStarted = true;
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.getLevelSource().levelExists(WORLD)) {
                mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
            } else {
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL,
                        true, rules, new WorldDataConfiguration(
                                net.minecraft.world.level.DataPackConfig.DEFAULT, FeatureFlags.DEFAULT_FLAGS));
                mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET)
                                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        new TitleScreen());
            }
        });
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (!autoMode()) return;
        Minecraft mc = Minecraft.getInstance();
        if (quitAtTick >= 0 && ++ticksInWorld >= quitAtTick) { mc.stop(); return; }
        if (autoOpened) return;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (++ticksInWorld < 40) return;
        autoOpened = true;
        if (!AUTO_DMGTEST.isEmpty()) {
            int attacks = Integer.parseInt(AUTO_DMGTEST.trim().split("\\s+")[0]);
            mc.player.connection.sendCommand("bhbdmgtest " + AUTO_DMGTEST.trim());
            quitAtTick = ticksInWorld + 120 + attacks * 30;
            return;
        }
        List<ResourceLocation> jobs = select(AUTO_FILTER);
        BromaxBattle.LOGGER.info("[BHB] Animation preview (auto): {} animations match '{}'", jobs.size(), AUTO_FILTER);
        mc.setScreen(new AnimationPreviewScreen(jobs, mc::stop, AUTO_GRIPS));
    }
}
