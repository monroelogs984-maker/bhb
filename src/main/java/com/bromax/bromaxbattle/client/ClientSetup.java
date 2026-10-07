package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Set;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientSetup {

    public static void register(IEventBus modBus) {
        modBus.addListener(ClientSetup::onClientSetup);
        NeoForge.EVENT_BUS.register(new ClientSetup());
        GuardClient.register(modBus);
        com.bromax.bromaxbattle.client.preview.AnimationPreview.register();
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Set<ResourceLocation> ids = WeaponRegistry.INSTANCE.getAllAnimationIds();
            AnimationRegistry.INSTANCE.load(ids);
        });
    }

    /**
     * Draws 16×2-pixel colored bars just below the crosshair for the next attack's variant type:
     * red = heavy, blue = light, white = default. Each fills left-to-right as its cooldown
     * recovers and only shows while recovering. The main-hand bar sits 9px below the crosshair
     * center; the off-hand bar sits under it. Guard marks bracket the crosshair.
     */
    @SubscribeEvent
    public void onRenderGuiLayerPost(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;

        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        GuiGraphics g = event.getGuiGraphics();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        if (WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem()) != null) {
            float strength = player.getAttackStrengthScale(0.0f);
            if (strength > 0f && strength < 1.0f) drawBar(g, cx - 8, cy + 9, strength, VariantIndicator.getColor());
        }
        float off = com.bromax.bromaxbattle.overpower.client.DualWieldClient.cooldownProgress(partial);
        if (off > 0f && off < 1.0f) drawBar(g, cx - 8, cy + 14, off, VariantIndicator.getOffhandColor());

        GuardClient.renderMarks(g, cx, cy, partial);
    }

    private static void drawBar(GuiGraphics g, int barX, int barY, float progress, int color) {
        int fill = (int) (progress * 16f);
        g.fill(barX, barY, barX + 16,   barY + 2, 0x88000000);
        g.fill(barX, barY, barX + fill, barY + 2, color);
        // Bright leading-edge pixel
        if (fill > 0 && fill < 16) {
            g.fill(barX + fill - 1, barY, barX + fill, barY + 2, 0xFFFFFFFF);
        }
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) return;

        AnimationController.INSTANCE.tickAll();

        UUID pid = mc.player.getUUID();
        ItemStack held = mc.player.getMainHandItem();
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);

        if (attrs == null || attrs.idleAnimation == null) {
            AnimationController.INSTANCE.clearIdle(pid);
            return;
        }
        if (AnimationController.INSTANCE.isPlaying(pid)) return;

        var anim = AnimationRegistry.INSTANCE.get(attrs.idleAnimation);
        if (anim == null) return;

        AnimationController.INSTANCE.playIdleIfInactive(pid, anim);
    }
}
