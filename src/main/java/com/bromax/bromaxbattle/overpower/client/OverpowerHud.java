package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.overpower.config.OpConfig;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Two-sided Overpower bar drawn in place of the XP bar while it's in use.
 *
 *   [ pressure on you  |  your pressure on the target ]
 *   dark red from the centre leftward, bright red from the centre rightward.
 *
 * It flashes gold while a Glare Strike window is open, and the level-number slot shows the
 * target's pressure (or GLARE). After combat it lingers briefly, then the XP bar returns.
 */
@OnlyIn(Dist.CLIENT)
public class OverpowerHud {
    private static float self, target;
    private static int glareLeft, glareWindow;
    private static boolean glaring;
    private static long lastActiveTick = Long.MIN_VALUE;
    private static long receivedAtTick;

    public static void receive(OpNetwork.Hud p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        self = p.self();
        target = p.target();
        glareLeft = p.glareTicksLeft();
        glareWindow = p.glareWindow();
        glaring = p.glaring();
        receivedAtTick = mc.level.getGameTime();
        if (p.changed() || glareLeft > 0 || glaring) lastActiveTick = receivedAtTick;
    }

    /** Up when a bar changes, then back to the XP bar after lingerTicks without another change. */
    private static boolean visible(Minecraft mc) {
        if (mc.level == null || mc.player == null) return false;
        long now = mc.level.getGameTime();
        if (glaring || glareLeft - (now - receivedAtTick) > 0) return true;
        return now - lastActiveTick <= OpConfig.HUD_LINGER_TICKS.get();
    }

    @SubscribeEvent
    public void onLayer(RenderGuiLayerEvent.Pre event) {
        boolean bar = VanillaGuiLayers.EXPERIENCE_BAR.equals(event.getName());
        boolean level = VanillaGuiLayers.EXPERIENCE_LEVEL.equals(event.getName());
        if (!bar && !level) return;
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc) || mc.options.hideGui || mc.player.isSpectator()) return;
        event.setCanceled(true);
        if (bar) draw(event.getGuiGraphics(), mc);
        else drawLabel(event.getGuiGraphics(), mc);
    }

    private static void draw(GuiGraphics g, Minecraft mc) {
        int w = g.guiWidth(), h = g.guiHeight();
        int x0 = w / 2 - 91, y = h - 29, half = 91;
        long now = mc.level.getGameTime();
        int windowLeft = (int) Math.max(0, glareLeft - (now - receivedAtTick));
        boolean glareOpen = windowLeft > 0;
        boolean flash = glareOpen && (now / 2) % 2 == 0;

        g.fill(x0 - 1, y - 1, x0 + 183, y + 6, glareOpen ? (flash ? 0xFFFFD040 : 0xFF806010) : 0xFF1A0505);
        g.fill(x0, y, x0 + 182, y + 5, 0xFF2A0C0C);
        int left = Math.round(half * Math.min(1f, self / 100f));
        int right = Math.round(half * Math.min(1f, target / 100f));
        if (left > 0) g.fill(x0 + half - left, y, x0 + half, y + 5, glaring ? 0xFFFFC040 : 0xFF7A1010);
        if (right > 0) g.fill(x0 + half + 1, y, x0 + half + 1 + right, y + 5, 0xFFE02020);
        // highlight row and centre tick
        if (right > 0) g.fill(x0 + half + 1, y, x0 + half + 1 + right, y + 1, 0xFFFF7060);
        g.fill(x0 + half, y - 1, x0 + half + 1, y + 6, 0xFFE0C0A0);
        if (glareOpen && glareWindow > 0) {
            int span = Math.round(182f * windowLeft / glareWindow);
            g.fill(x0 + 91 - span / 2, y + 5, x0 + 91 + span / 2, y + 6, 0xFFFFD040);
        }
    }

    private static void drawLabel(GuiGraphics g, Minecraft mc) {
        long now = mc.level.getGameTime();
        boolean glareOpen = glareLeft - (now - receivedAtTick) > 0;
        Component text = glareOpen ? Component.translatable("hud.bromax_battle.glare")
                : Component.literal(Math.round(target) + "%");
        int color = glareOpen ? 0xFFD040 : (target >= 100f ? 0xFF4040 : 0xE05050);
        Font font = mc.font;
        int x = (g.guiWidth() - font.width(text)) / 2;
        int y = g.guiHeight() - 31 - 4;
        g.drawString(font, text, x + 1, y, 0, false);
        g.drawString(font, text, x - 1, y, 0, false);
        g.drawString(font, text, x, y + 1, 0, false);
        g.drawString(font, text, x, y - 1, 0, false);
        g.drawString(font, text, x, y, color, false);
    }
}
