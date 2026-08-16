package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.Set;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class ClientSetup {

    public static void register() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientSetup::onClientSetup);
        MinecraftForge.EVENT_BUS.register(new ClientSetup());
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Set<ResourceLocation> ids = WeaponRegistry.INSTANCE.getAllAnimationIds();
            AnimationRegistry.INSTANCE.load(ids);
        });
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
