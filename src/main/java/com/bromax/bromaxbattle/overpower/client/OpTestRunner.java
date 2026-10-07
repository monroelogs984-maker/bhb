package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.client.VariantIndicator;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;
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
        mc.options.pauseOnLostFocus = false;
        if (Boolean.getBoolean("bhb.guardfp.tune")) {
            tuneGuard(mc, ticks - 60);
            return;
        }
        if (!sent && ticks >= 60 && mc.screen == null) {
            sent = true;
            mc.player.connection.sendCommand("optest");
        }
        if (!sent) return;
        // Screenshots of the HUD through the server phases
        int t = ticks - 60;
        if (t > 0 && t <= 300 && t % 15 == 0) shot(mc, "hud_" + t);
        if (clientStart < 0) {
            if (com.bromax.bromaxbattle.overpower.debug.OpTest.serverPhaseDone() && t > 300) clientStart = ticks;
            if (ticks > 60 + 2400) mc.stop(); // server phases never finished
            return;
        }
        clientPhase(mc, ticks - clientStart);
    }

    private int clientStart = -1;

    /**
     * E) the real client paths: main-hand clicks and right-click off-hand attacks (the server's
     * variant must match the one animated), both cooldown bars, the guard key, a blocked hit,
     * and the guard pose. Then the guidebook.
     */
    private void clientPhase(Minecraft mc, int c) {
        Entity zombie = nearestZombie(mc);
        if (zombie == null) return;
        // Losing window focus must not pause the game or turn the camera off the zombie
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        if (c < 440) mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                zombie.position().add(0, zombie.getBbHeight() * 0.6, 0));
        // 10 main-hand attacks, 14 ticks apart
        if (c >= 10 && c < 150 && (c - 10) % 14 == 0) {
            mc.gameMode.attack(mc.player, zombie);
            mc.player.swing(InteractionHand.MAIN_HAND);
            log("E client main variant: %s", com.bromax.bromaxbattle.animation.AnimationController.INSTANCE
                    .currentAnimation(mc.player.getUUID(), false));
        }
        // 8 off-hand attacks through the use key
        if (c >= 180 && c < 292 && (c - 180) % 14 == 0) KeyMapping.click(mc.options.keyUse.getKey());
        if (c >= 181 && c < 293 && (c - 181) % 14 == 0) log("E client offhand variant: %s (bar %.2f)",
                com.bromax.bromaxbattle.animation.AnimationController.INSTANCE.currentAnimation(mc.player.getUUID(), true),
                DualWieldClient.cooldownProgress(0f));
        // Both bars at once
        if (c == 320) { mc.gameMode.attack(mc.player, zombie); mc.player.swing(InteractionHand.MAIN_HAND); }
        if (c == 323) KeyMapping.click(mc.options.keyUse.getKey());
        if (c == 326) shot(mc, "bars");
        // Guard: Tab, blocked hit, cooldown, pose
        if (c == 350) KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_TAB));
        if (c == 356) { log("E guard after Tab: %s", com.bromax.bromaxbattle.client.GuardClient.isGuarding(mc.player)); shot(mc, "guard_up"); }
        if (c == 360) mc.player.connection.sendCommand("optest hit");
        if (c == 364) { log("E guard after hit: %s", com.bromax.bromaxbattle.client.GuardClient.isGuarding(mc.player)); shot(mc, "guard_cooldown"); }
        if (c == 400) KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_TAB));
        if (c == 410) mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        if (c == 416) shot(mc, "guard_pose_front");
        if (c == 418) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        if (c == 424) shot(mc, "guard_pose_back");
        if (c == 426) { mc.options.setCameraType(CameraType.FIRST_PERSON); KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_TAB)); }
        if (c == 432) log("E guard after second Tab: %s", com.bromax.bromaxbattle.client.GuardClient.isGuarding(mc.player));
        if (c == 440) openGuide();
        if (c == 460) shot(mc, "guide");
        if (c == 480) mc.stop();
    }

    /** First-person guard placement candidates {x, y, z, roll, yaw}, one screenshot each. */
    private static final float[][] GUARD_FP = {
            {-0.25f, -0.20f, -0.30f, 40f, 35f}, {-0.20f, -0.20f, -0.30f, 45f, 35f}, {-0.25f, -0.15f, -0.30f, 40f, 40f},
            {-0.30f, -0.20f, -0.30f, 45f, 40f}, {-0.15f, -0.20f, -0.35f, 40f, 35f}, {-0.25f, -0.25f, -0.25f, 50f, 35f}};

    private void tuneGuard(Minecraft mc, int t) {
        if (t == 0) {
            mc.player.connection.sendCommand("item replace entity @s weapon.mainhand with iron_sword");
            mc.player.setXRot(0f);
        }
        if (t == 20) KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_TAB));
        int i = (t - 40) / 10;
        if (t >= 40 && i < GUARD_FP.length) {
            float[] v = GUARD_FP[i];
            com.bromax.bromaxbattle.client.GuardClient.FP_X = v[0];
            com.bromax.bromaxbattle.client.GuardClient.FP_Y = v[1];
            com.bromax.bromaxbattle.client.GuardClient.FP_Z = v[2];
            com.bromax.bromaxbattle.client.GuardClient.FP_ROLL = v[3];
            com.bromax.bromaxbattle.client.GuardClient.FP_YAW = v[4];
            if ((t - 40) % 10 == 8) shot(mc, "guardfp_" + i);
        }
        if (t == 40 + GUARD_FP.length * 10 + 10) mc.stop();
    }

    private static String offhandKind() {
        int color = VariantIndicator.getOffhandColor();
        return color == 0xFFFF4444 ? "HEAVY" : color == 0xFF44AAFF ? "LIGHT" : "DEFAULT";
    }

    private static Entity nearestZombie(Minecraft mc) {
        Entity best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.monster.Zombie
                    && (best == null || e.distanceToSqr(mc.player) < best.distanceToSqr(mc.player))) best = e;
        }
        return best;
    }

    private static void log(String fmt, Object... args) {
        com.bromax.bromaxbattle.BromaxBattle.LOGGER.info("[OpTest] " + String.format(java.util.Locale.ROOT, fmt, args));
    }
}
