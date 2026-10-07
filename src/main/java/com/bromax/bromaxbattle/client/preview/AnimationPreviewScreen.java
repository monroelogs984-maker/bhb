package com.bromax.bromaxbattle.client.preview;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.animation.BoneTarget;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders one contact sheet per animation and saves it as a screenshot:
 * columns are 5 points in time, rows are camera angles. The pose comes from
 * the real render path (PlayerModel + ItemInHandLayer mixins) on a client-only
 * dummy player, so the sheet shows exactly what the game draws.
 *
 * Output: screenshots/bhb_preview/<animation>.png
 */
@OnlyIn(Dist.CLIENT)
public class AnimationPreviewScreen extends Screen {

    /** {label, yaw degrees (0 = facing the camera), pitch degrees (positive = looking down)} */
    private static final Object[][] VIEWS = {
        {"front 3/4", 35f, 12f},
        {"right side", 90f, 5f},
        {"back 3/4", 215f, 12f},
        {"top", 20f, 65f},
    };
    private static final int HEADER = 22;
    private static final int LABEL_W = 62;

    /** Grip comparison mode: columns are the grip presets at one tick instead of ticks. */
    private final boolean gripMode;
    private final List<ResourceLocation> jobs;
    private final Runnable onDone;
    private final UUID dummyId = UUID.nameUUIDFromBytes("bhb_preview_dummy".getBytes());
    private RemotePlayer dummy;
    private int index = 0;
    private int framesOnCurrent = 0;
    private int saved = 0;

    public AnimationPreviewScreen(List<ResourceLocation> jobs, Runnable onDone) {
        this(jobs, onDone, false);
    }

    public AnimationPreviewScreen(List<ResourceLocation> jobs, Runnable onDone, boolean gripMode) {
        super(Component.literal("BHB animation preview"));
        this.gripMode = gripMode;
        this.jobs = jobs;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (dummy == null && mc.level != null) {
            dummy = new RemotePlayer(mc.level, new GameProfile(dummyId, "BHB"));
        }
        new File(mc.gameDirectory, "screenshots/bhb_preview").mkdirs();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        g.fill(0, 0, width, height, 0xFF8E9AA6);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Tutorial hints and other toasts draw on top of screens and would cover the sheet
        Minecraft.getInstance().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        Minecraft.getInstance().getToasts().clear();
        renderBackground(g);
        if (dummy == null || index >= jobs.size()) return;

        ResourceLocation id = jobs.get(index);
        AnimationDefinition anim = AnimationRegistry.INSTANCE.get(id);
        if (anim == null) return;

        ItemStack stack = itemFor(id.getPath());
        dummy.setItemSlot(EquipmentSlot.MAINHAND, stack);
        float[] ticks;
        String[] labels;
        com.bromax.bromaxbattle.animation.WeaponGrip[] grips = null;
        if (gripMode) {
            // Strike pose, where the grip is fully in
            grips = com.bromax.bromaxbattle.animation.WeaponGrip.values();
            // the striking arm's most extended keyframe inside the hit window, else the hit end
            float strike = anim.hitWindowEnd;
            float best = Float.NEGATIVE_INFINITY;
            for (var kf : anim.keyframes(BoneTarget.RIGHT_ARM)) {
                if (kf.tick >= anim.hitWindowStart && kf.tick <= anim.hitWindowEnd && kf.rx > best) {
                    best = kf.rx;
                    strike = kf.tick;
                }
            }
            ticks = new float[grips.length];
            labels = new String[grips.length];
            for (int i = 0; i < grips.length; i++) {
                ticks[i] = strike;
                labels[i] = "grip " + grips[i].name().toLowerCase(Locale.ROOT) + (grips[i] == anim.grip ? " (current)" : "");
            }
        } else {
            ticks = sampleTicks(anim);
            labels = labelTicks(anim, ticks);
        }

        text(g, String.format(Locale.ROOT,
                "%s   duration %d   hit %d-%d   grip %s x%.2f   lunge %.2f   item %s",
                id.getPath(), anim.duration, anim.hitWindowStart, anim.hitWindowEnd,
                anim.grip.name().toLowerCase(Locale.ROOT), anim.gripScale, anim.lunge,
                stack.getItem().toString()), 4, 3, width - 8, 0xFFFFFFFF, false);

        int cols = ticks.length;
        int cellW = (width - LABEL_W) / cols;
        int cellH = (height - HEADER) / VIEWS.length;
        float scale = cellH / 2.9f;

        for (int c = 0; c < cols; c++) {
            int x0 = LABEL_W + c * cellW;
            text(g, gripMode ? String.format(Locale.ROOT, "t=%.1f %s", ticks[c], labels[c]) : String.format(Locale.ROOT, "t=%.0f %s", ticks[c], labels[c]),
                    x0 + cellW / 2, HEADER - 9, cellW - 6, 0xFFFFFF55, true);
            g.fill(x0, HEADER, x0 + 1, height, 0x66000000);
        }
        boolean hideGui = Minecraft.getInstance().options.hideGui;
        Minecraft.getInstance().options.hideGui = true; // no name tag over the dummy
        for (int r = 0; r < VIEWS.length; r++) {
            int y0 = HEADER + r * cellH;
            g.fill(0, y0, width, y0 + 1, 0x66000000);
            text(g, (String) VIEWS[r][0], 3, y0 + cellH / 2, LABEL_W - 6, 0xFFFFFF55, false);
            float yaw = (Float) VIEWS[r][1];
            float pitch = (Float) VIEWS[r][2];

            for (int c = 0; c < cols; c++) {
                int cx = LABEL_W + c * cellW + cellW / 2;
                int cy = y0 + cellH / 2 + (int) (cellH * (pitch > 45f ? -0.12f : 0.10f));
                AnimationController.INSTANCE.pose(dummyId, anim, ticks[c], grips != null ? grips[c] : null);
                resetDummyRotation();
                Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI)
                        .rotateX((float) Math.toRadians(-pitch))
                        .rotateY((float) Math.toRadians(yaw));
                // 1.20.1 takes the feet position and an int scale
                InventoryScreen.renderEntityInInventory(g, cx, (int) (cy + dummy.getBbHeight() * scale / 2f),
                        Math.round(scale), pose, null, dummy);
            }
        }
        Minecraft.getInstance().options.hideGui = hideGui;
        AnimationController.INSTANCE.clear(dummyId);
        framesOnCurrent++;
    }

    /** Called between frames, when the main render target still holds the last finished sheet. */
    @Override
    public void tick() {
        if (index >= jobs.size()) {
            finish();
            return;
        }
        if (framesOnCurrent < 2) return;

        Minecraft mc = Minecraft.getInstance();
        String name = "bhb_preview/" + jobs.get(index).getPath() + (gripMode ? "_grips" : "") + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {});
        saved++;
        index++;
        framesOnCurrent = 0;
        if (index >= jobs.size()) finish();
    }

    private void finish() {
        BromaxBattle.LOGGER.info("[BHB] Animation preview: saved {} sheets to screenshots/bhb_preview", saved);
        AnimationController.INSTANCE.clear(dummyId);
        index = Integer.MAX_VALUE;
        if (onDone != null) onDone.run();
        else Minecraft.getInstance().setScreen(null);
    }

    /** Draws text shrunk to fit {@code maxW}; x is the left edge, or the center when {@code centered}. */
    private void text(GuiGraphics g, String s, int x, int y, int maxW, int color, boolean centered) {
        float k = Math.min(1f, maxW / (float) Math.max(1, font.width(s)));
        g.pose().pushPose();
        g.pose().translate(centered ? x - font.width(s) * k / 2f : x, y, 0f);
        g.pose().scale(k, k, 1f);
        g.drawString(font, s, 0, 0, color);
        g.pose().popPose();
    }

    private void resetDummyRotation() {
        dummy.yBodyRot = dummy.yBodyRotO = 180f;
        dummy.setYRot(180f);
        dummy.yRotO = 180f;
        dummy.yHeadRot = dummy.yHeadRotO = 180f;
        dummy.setXRot(0f);
        dummy.xRotO = 0f;
    }

    private static final int MAX_COLUMNS = 6;

    /**
     * The striking arm's keyframe ticks (the poses the animation is authored around) plus the
     * hit window bounds, minus tick 0. Thinned evenly when there are more than MAX_COLUMNS.
     */
    static float[] sampleTicks(AnimationDefinition a) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>(a.keyframeTicks(BoneTarget.RIGHT_ARM));
        set.add(a.hitWindowStart);
        set.add(a.hitWindowEnd);
        set.add(a.duration);
        set.remove(0);
        set.removeIf(t -> t < 0 || t > a.duration);
        List<Integer> all = new java.util.ArrayList<>(set);
        if (all.size() > MAX_COLUMNS) {
            List<Integer> thin = new java.util.ArrayList<>();
            for (int i = 0; i < MAX_COLUMNS; i++) thin.add(all.get(Math.round(i * (all.size() - 1) / (float) (MAX_COLUMNS - 1))));
            all = thin;
        }
        float[] out = new float[all.size()];
        for (int i = 0; i < out.length; i++) out[i] = all.get(i);
        return out;
    }

    static String[] labelTicks(AnimationDefinition a, float[] ticks) {
        List<Integer> kfs = a.keyframeTicks(BoneTarget.RIGHT_ARM);
        String[] out = new String[ticks.length];
        for (int i = 0; i < ticks.length; i++) {
            int t = (int) ticks[i];
            List<String> parts = new java.util.ArrayList<>();
            if (t < a.hitWindowStart) parts.add("windup");
            if (t == a.hitWindowStart) parts.add("hit start");
            else if (t == a.hitWindowEnd) parts.add("hit end");
            else if (t > a.hitWindowStart && t < a.hitWindowEnd) parts.add("in hit");
            if (t > a.hitWindowEnd) parts.add(t == a.duration ? "end" : "recovery");
            if (kfs.contains(t)) parts.add("[key]");
            out[i] = String.join(" ", parts);
        }
        return out;
    }

    /** A vanilla stand-in item that roughly matches the weapon category's silhouette. */
    static ItemStack itemFor(String animPath) {
        String p = animPath.toLowerCase(Locale.ROOT);
        if (p.startsWith("crossbow")) return new ItemStack(Items.CROSSBOW);
        if (p.startsWith("bow") || p.startsWith("longbow")) return new ItemStack(Items.BOW);
        if (p.contains("spear") || p.contains("pike") || p.contains("javelin") || p.contains("trident")
                || p.contains("glaive") || p.contains("halberd") || p.contains("lance") || p.contains("staff")) {
            return new ItemStack(Items.TRIDENT);
        }
        if (p.contains("hammer") || p.contains("mace") || p.contains("club") || p.contains("flail")) {
            return new ItemStack(Items.IRON_SHOVEL); // no mace before 1.21
        }
        if (p.contains("axe")) return new ItemStack(Items.IRON_AXE);
        if (p.contains("scythe") || p.contains("sickle")) return new ItemStack(Items.IRON_HOE);
        return new ItemStack(Items.IRON_SWORD);
    }
}
