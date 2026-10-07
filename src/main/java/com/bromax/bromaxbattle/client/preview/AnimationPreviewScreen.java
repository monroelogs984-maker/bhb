package com.bromax.bromaxbattle.client.preview;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.AnimationDefinition;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import com.bromax.bromaxlib.animation.BoneTarget;
import com.bromax.bromaxlib.animation.Keyframe;
import com.bromax.bromaxlib.animation.WeaponGrip;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Renders one contact sheet per animation and saves it as a screenshot: columns are points in
 * time (or grip presets in grip mode), rows are camera angles. The pose comes from the real render
 * path (BROMAX's Lib ModelPlayer + ItemRenderer mixins) on a client-only dummy player.
 *
 * Output: screenshots/bhb_preview/<animation>.png
 */
@SideOnly(Side.CLIENT)
public class AnimationPreviewScreen extends GuiScreen {

    /** {label, yaw degrees (0 = facing the camera), pitch degrees (positive = looking down)} */
    private static final Object[][] VIEWS = {
        {"front 3/4", 35f, 12f},
        {"right side", 90f, 5f},
        {"back 3/4", 215f, 12f},
        {"top", 20f, 65f},
    };
    private static final int HEADER = 22;
    private static final int LABEL_W = 62;
    private static final int MAX_COLUMNS = 6;

    private final boolean gripMode;
    private final List<ResourceLocation> jobs;
    private final Runnable onDone;
    private final UUID dummyId = UUID.nameUUIDFromBytes("bhb_preview_dummy".getBytes());
    private EntityOtherPlayerMP dummy;
    private int index = 0;
    private int framesOnCurrent = 0;
    private int saved = 0;

    public AnimationPreviewScreen(List<ResourceLocation> jobs, Runnable onDone, boolean gripMode) {
        this.jobs = jobs;
        this.onDone = onDone;
        this.gripMode = gripMode;
    }

    @Override
    public void initGui() {
        if (dummy == null && mc.world != null) {
            dummy = new EntityOtherPlayerMP(mc.world, new GameProfile(dummyId, "BHB"));
        }
        new File(mc.mcDataDir, "screenshots/bhb_preview").mkdirs();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Tutorial hints and toasts draw over screens and would cover the sheet
        mc.getTutorial().setStep(TutorialSteps.NONE);
        mc.getToastGui().clear();
        drawRect(0, 0, width, height, 0xFF8E9AA6);
        if (dummy == null || index >= jobs.size()) return;

        ResourceLocation id = jobs.get(index);
        AnimationDefinition anim = AnimationRegistry.INSTANCE.get(id);
        if (anim == null) return;

        ItemStack stack = itemFor(id.getResourcePath());
        dummy.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, stack);

        float[] ticks;
        String[] labels;
        WeaponGrip[] grips = null;
        if (gripMode) {
            grips = WeaponGrip.values();
            float strike = anim.hitWindowEnd;
            float best = Float.NEGATIVE_INFINITY;
            for (Keyframe kf : anim.keyframes(BoneTarget.RIGHT_ARM)) {
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

        text(String.format(Locale.ROOT,
                "%s   duration %d   hit %d-%d   grip %s x%.2f   lunge %.2f   item %s",
                id.getResourcePath(), anim.duration, anim.hitWindowStart, anim.hitWindowEnd,
                anim.grip.name().toLowerCase(Locale.ROOT), anim.gripScale, anim.lunge,
                stack.getItem().getRegistryName()), 4, 3, width - 8, 0xFFFFFFFF, false);

        int cols = ticks.length;
        int cellW = (width - LABEL_W) / cols;
        int cellH = (height - HEADER) / VIEWS.length;
        float scale = cellH / 2.9f;

        for (int c = 0; c < cols; c++) {
            int x0 = LABEL_W + c * cellW;
            text(String.format(Locale.ROOT, gripMode ? "t=%.1f %s" : "t=%.0f %s", ticks[c], labels[c]),
                    x0 + cellW / 2, HEADER - 9, cellW - 6, 0xFFFFFF55, true);
            drawRect(x0, HEADER, x0 + 1, height, 0x66000000);
        }
        boolean hideGui = mc.gameSettings.hideGUI;
        mc.gameSettings.hideGUI = true; // no name tag over the dummy
        for (int r = 0; r < VIEWS.length; r++) {
            int y0 = HEADER + r * cellH;
            drawRect(0, y0, width, y0 + 1, 0x66000000);
            text((String) VIEWS[r][0], 3, y0 + cellH / 2, LABEL_W - 6, 0xFFFFFF55, false);
            float yaw = (Float) VIEWS[r][1];
            float pitch = (Float) VIEWS[r][2];
            for (int c = 0; c < cols; c++) {
                int cx = LABEL_W + c * cellW + cellW / 2;
                int cy = y0 + cellH / 2 + (int) (cellH * (pitch > 45f ? -0.12f : 0.10f));
                AnimationController.INSTANCE.pose(dummyId, anim, ticks[c], grips != null ? grips[c] : null);
                resetDummyRotation();
                renderDummy(cx, (int) (cy + dummy.height * scale / 2f), scale, yaw, pitch, dummy);
            }
        }
        mc.gameSettings.hideGUI = hideGui;
        AnimationController.INSTANCE.clear(dummyId);
        framesOnCurrent++;
    }

    /** Called between frames, when the framebuffer still holds the last finished sheet. */
    @Override
    public void updateScreen() {
        if (index >= jobs.size()) {
            finish();
            return;
        }
        if (framesOnCurrent < 2) return;
        String name = "bhb_preview/" + jobs.get(index).getResourcePath() + (gripMode ? "_grips" : "") + ".png";
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        saved++;
        index++;
        framesOnCurrent = 0;
        if (index >= jobs.size()) finish();
    }

    private void finish() {
        if (index == Integer.MAX_VALUE) return;
        BromaxBattle.LOGGER.info("[BHB] Animation preview: saved {} sheets to screenshots/bhb_preview", saved);
        AnimationController.INSTANCE.clear(dummyId);
        index = Integer.MAX_VALUE;
        if (onDone != null) onDone.run();
        else mc.displayGuiScreen(null);
    }

    private void resetDummyRotation() {
        dummy.renderYawOffset = dummy.prevRenderYawOffset = 180f;
        dummy.rotationYaw = dummy.prevRotationYaw = 180f;
        dummy.rotationYawHead = dummy.prevRotationYawHead = 180f;
        dummy.rotationPitch = dummy.prevRotationPitch = 0f;
        dummy.limbSwingAmount = dummy.prevLimbSwingAmount = 0f;
    }

    /** GuiInventory.drawEntityOnScreen with an explicit view rotation. (x, y) is the feet position. */
    private static void renderDummy(int x, int y, float scale, float yaw, float pitch, EntityOtherPlayerMP entity) {
        GlStateManager.enableColorMaterial();
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, 50.0F);
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        // 1.12.2's GUI entity transform (scale -s,s,s + Z 180) is the modern one turned 180 about Y,
        // which also flips the pitch direction: +pitch and yaw+180 give the newer branches' views
        GlStateManager.rotate(pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(yaw + 180.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        RenderManager rm = Minecraft.getMinecraft().getRenderManager();
        rm.setPlayerViewY(180.0F);
        rm.setRenderShadow(false);
        rm.renderEntity(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
        rm.setRenderShadow(true);
        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /** Draws text shrunk to fit {@code maxW}; x is the left edge, or the center when {@code centered}. */
    private void text(String s, int x, int y, int maxW, int color, boolean centered) {
        float k = Math.min(1f, maxW / (float) Math.max(1, fontRenderer.getStringWidth(s)));
        GlStateManager.pushMatrix();
        GlStateManager.translate(centered ? x - fontRenderer.getStringWidth(s) * k / 2f : x, y, 0f);
        GlStateManager.scale(k, k, 1f);
        fontRenderer.drawStringWithShadow(s, 0, 0, color);
        GlStateManager.popMatrix();
    }

    /**
     * The striking arm's keyframe ticks (the poses the animation is authored around) plus the
     * hit window bounds, minus tick 0. Thinned evenly when there are more than MAX_COLUMNS.
     */
    static float[] sampleTicks(AnimationDefinition a) {
        TreeSet<Integer> set = new TreeSet<>(a.keyframeTicks(BoneTarget.RIGHT_ARM));
        set.add(a.hitWindowStart);
        set.add(a.hitWindowEnd);
        set.add(a.duration);
        set.remove(0);
        set.removeIf(t -> t < 0 || t > a.duration);
        List<Integer> all = new ArrayList<>(set);
        if (all.size() > MAX_COLUMNS) {
            List<Integer> thin = new ArrayList<>();
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
            List<String> parts = new ArrayList<>();
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

    /** A vanilla stand-in item that roughly matches the weapon category's silhouette (1.12.2 set). */
    static ItemStack itemFor(String animPath) {
        String p = animPath.toLowerCase(Locale.ROOT);
        if (p.startsWith("bow") || p.startsWith("longbow") || p.startsWith("shortbow") || p.startsWith("crossbow")) {
            return new ItemStack(Items.BOW);
        }
        if (p.contains("spear") || p.contains("pike") || p.contains("javelin") || p.contains("trident")
                || p.contains("glaive") || p.contains("halberd") || p.contains("lance") || p.contains("staff")) {
            return new ItemStack(Items.STICK);
        }
        if (p.contains("hammer") || p.contains("mace") || p.contains("club") || p.contains("flail")) {
            return new ItemStack(Items.IRON_SHOVEL);
        }
        if (p.contains("axe")) return new ItemStack(Items.IRON_AXE);
        if (p.contains("scythe") || p.contains("sickle")) return new ItemStack(Items.IRON_HOE);
        return new ItemStack(Items.IRON_SWORD);
    }
}
