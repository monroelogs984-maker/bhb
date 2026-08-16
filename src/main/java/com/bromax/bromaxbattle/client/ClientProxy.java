package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.CommonProxy;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.AnimationDefinition;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.UUID;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {}

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event); // registers CombatHandler — must not be skipped
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || mc.isGamePaused()) return;

        UUID pid = mc.player.getUniqueID();
        ItemStack held = mc.player.getHeldItemMainhand();
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);

        // Clear idle when the player is no longer holding a weapon that has one
        if (attrs == null || attrs.idleAnimation == null) {
            AnimationController.INSTANCE.clearIdle(pid);
            return;
        }

        // Don't start idle while an attack animation is playing or blending out
        if (AnimationController.INSTANCE.isPlaying(pid)) return;

        AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attrs.idleAnimation);
        if (anim == null) return;

        AnimationController.INSTANCE.playIdleIfInactive(pid, anim);
    }
}
