package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/** Guard key, the held guard pose (own and other players'), and the crosshair guard marks. */
@OnlyIn(Dist.CLIENT)
public final class GuardClient {
    public static final KeyMapping KEY = new KeyMapping("key.bromax_battle.guard", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB, "key.categories.bromax_battle");

    private static final Set<Integer> GUARDING = new HashSet<>();
    /** Local player's guard raise/lower times, for the first-person ease. */
    private static long raisedAt = Long.MIN_VALUE / 2;
    private static long loweredAt = Long.MIN_VALUE / 2;
    private static final int EASE_TICKS = 4;
    // First-person guard placement (camera space, applied before vanilla's hand offset): blade
    // across the lower right, tip up-left short of the crosshair; the yaw turns its flat face to
    // the item light. Public so -PguardFpTune can try candidates.
    public static float FP_X = -0.25f;
    public static float FP_Y = -0.20f;
    public static float FP_Z = -0.30f;
    public static float FP_ROLL = 40f;
    public static float FP_YAW = 35f;
    private static long cooldownStart;
    private static long cooldownUntil;

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(KEY));
        NeoForge.EVENT_BUS.register(new GuardClient());
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            GUARDING.clear();
            return;
        }
        while (KEY.consumeClick()) {
            if (mc.screen == null) PacketDistributor.sendToServer(new OpNetwork.GuardToggle());
        }
    }

    /** Server says a player's guard went up or down. */
    public static void receive(int entityId, boolean up, int cooldownTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (!(e instanceof Player player)) return;
        if (player == mc.player) {
            if (up) raisedAt = mc.level.getGameTime();
            else if (GUARDING.contains(entityId)) loweredAt = mc.level.getGameTime();
        }
        if (up) {
            GUARDING.add(entityId);
            AnimationDefinition pose = poseFor(player);
            if (pose != null) AnimationController.INSTANCE.hold(player.getUUID(), pose);
        } else {
            GUARDING.remove(entityId);
            for (var id : AnimationRegistry.EXTRA_IDS) {
                AnimationDefinition pose = AnimationRegistry.INSTANCE.get(id);
                if (pose != null) AnimationController.INSTANCE.release(player.getUUID(), pose);
            }
            if (player == mc.player && cooldownTicks > 0) {
                cooldownStart = mc.level.getGameTime();
                cooldownUntil = cooldownStart + cooldownTicks;
            }
        }
    }

    private static AnimationDefinition poseFor(Player player) {
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
        // Both hands on the grip only when the off hand is free
        boolean twoHanded = attrs != null && attrs.category.isTwoHanded() && player.getOffhandItem().isEmpty();
        return AnimationRegistry.INSTANCE.get(twoHanded ? AnimationRegistry.GUARD_TWO_HANDED : AnimationRegistry.GUARD_ONE_HANDED);
    }

    public static boolean isGuardAnimation(net.minecraft.resources.ResourceLocation id) {
        return id != null && AnimationRegistry.EXTRA_IDS.contains(id);
    }

    /** First-person guard: eases the main-hand weapon across the view over EASE_TICKS, and back. */
    public static void applyFirstPerson(com.mojang.blaze3d.vertex.PoseStack pose, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float now = mc.level.getGameTime() + partialTick;
        float w = isGuarding(mc.player)
                ? Math.min(1f, (now - raisedAt) / EASE_TICKS)
                : 1f - Math.min(1f, (now - loweredAt) / EASE_TICKS);
        if (w <= 0f) return;
        w = w * w * (3f - 2f * w); // smoothstep
        pose.translate(FP_X * w, FP_Y * w, FP_Z * w);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(FP_ROLL * w));
        if (FP_YAW != 0f) pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(FP_YAW * w));
    }

    public static boolean isGuarding(Player player) {
        return GUARDING.contains(player.getId());
    }

    /**
     * Brackets either side of the crosshair: bright while the guard is up, red and closing in while
     * it's recovering after a block.
     */
    static void renderMarks(GuiGraphics g, int cx, int cy, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int gap, color;
        if (isGuarding(mc.player)) {
            gap = 7;
            color = 0xFFE0E0E0;
        } else {
            float now = mc.level.getGameTime() + partialTick;
            if (now >= cooldownUntil) return;
            float left = (cooldownUntil - now) / Math.max(1f, cooldownUntil - cooldownStart);
            gap = 7 + Math.round(left * 4);
            color = 0xCCFF4444;
        }
        // [ ]: 1px uprights with 2px feet
        for (int side = -1; side <= 1; side += 2) {
            int x = cx + side * gap;
            g.fill(x, cy - 4, x + 1, cy + 5, color);
            int foot = side < 0 ? x : x - 1;
            g.fill(foot, cy - 4, foot + 2, cy - 3, color);
            g.fill(foot, cy + 4, foot + 2, cy + 5, color);
        }
    }
}
