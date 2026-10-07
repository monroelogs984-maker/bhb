package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;

import java.util.Set;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientSetup {

    public static void register(IEventBus modBus) {
        modBus.addListener(ClientSetup::onClientSetup);
        MinecraftForge.EVENT_BUS.register(new ClientSetup());
        com.bromax.bromaxbattle.client.preview.AnimationPreview.register();
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Set<ResourceLocation> ids = WeaponRegistry.INSTANCE.getAllAnimationIds();
            AnimationRegistry.INSTANCE.load(ids);
        });
    }

    /**
     * Draws a 16×2-pixel colored bar just below the crosshair to indicate the
     * next attack's variant type:  red = heavy,  blue = light,  white = default.
     *
     * The bar fills left-to-right as the attack cooldown recovers, matching the
     * vanilla cooldown rhythm. Only shows when holding a BHB weapon and the
     * cooldown is actively recovering (0 < scale < 1).
     */
    @SubscribeEvent
    public void onRenderGuiLayerPost(RenderGuiOverlayEvent.Post event) {
        if (!VanillaGuiOverlay.CROSSHAIR.id().equals(event.getOverlay().id())) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;

        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getMainHandItem());
        if (attrs == null) return;

        float strength = player.getAttackStrengthScale(0.0f);
        if (strength <= 0f || strength >= 1.0f) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int cx = sw / 2;
        int cy = sh / 2;

        // Position: 8px below the crosshair center
        int barY = cy + 9;
        int barX = cx - 8;
        int fill  = (int) (strength * 16f);
        int color = VariantIndicator.getColor();
        // Darken color for background
        int bg    = (color & 0xFF000000) | ((color & 0xFEFEFE) >> 1);

        PoseStack ps = event.getPoseStack();
        GuiComponent.fill(ps, barX,          barY, barX + 16,   barY + 2, 0x88000000);
        GuiComponent.fill(ps, barX,          barY, barX + fill,  barY + 2, color);
        // Bright leading-edge pixel
        if (fill > 0 && fill < 16) {
            GuiComponent.fill(ps, barX + fill - 1, barY, barX + fill, barY + 2, 0xFFFFFFFF);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return; // NeoForge fires .Post only
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
