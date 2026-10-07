package com.bromax.bromaxbattle.client;

import com.bromax.bromaxbattle.CommonProxy;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.AnimationDefinition;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Locale;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event); // registers CombatHandler — must not be skipped
        MinecraftForge.EVENT_BUS.register(this);
        AnimationController.offArmResolver = ClientProxy::offArmMode;
        com.bromax.bromaxbattle.client.preview.AnimationPreview.register();
    }

    /**
     * Off-arm mode for BROMAX's Lib from the animation's weapon category (longest category name
     * that prefixes the id: hunters_knife_* beats a shorter match). Paired weapons and bows keep
     * their authored left arm.
     */
    private static AnimationController.OffArmMode offArmMode(AnimationDefinition def) {
        WeaponCategory cat = categoryOf(def);
        if (cat == null || cat.isRanged()) return AnimationController.OffArmMode.AUTHORED;
        switch (cat) {
            case GAUNTLETS: case CLAW: case SAI: case NUNCHAKU:
                return AnimationController.OffArmMode.AUTHORED;
            default:
                return cat.isTwoHanded() ? AnimationController.OffArmMode.TWO_HANDED
                                         : AnimationController.OffArmMode.ONE_HANDED;
        }
    }

    public static WeaponCategory categoryOf(AnimationDefinition def) {
        if (def == null || def.id == null) return null;
        String path = def.id.getResourcePath().toUpperCase(Locale.ROOT);
        WeaponCategory best = null;
        for (WeaponCategory c : WeaponCategory.values()) {
            if (path.startsWith(c.name() + "_") && (best == null || c.name().length() > best.name().length())) best = c;
        }
        return best;
    }


    /**
     * Draws a 16×2-pixel colored bar just below the crosshair to indicate the
     * next attack's variant type:  red = heavy,  blue = light,  white = default.
     * Fills as the attack cooldown recovers; only while holding a BHB weapon.
     */
    @SubscribeEvent
    public void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.CROSSHAIRS) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null || mc.gameSettings.hideGUI) return;

        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getHeldItemMainhand());
        if (attrs == null) return;

        float strength = player.getCooledAttackStrength(0.0f);
        if (strength <= 0f || strength >= 1.0f) return;

        ScaledResolution res = event.getResolution();
        int cx = res.getScaledWidth() / 2;
        int cy = res.getScaledHeight() / 2;
        int barY = cy + 9;
        int barX = cx - 8;
        int fill  = (int) (strength * 16f);
        int color = VariantIndicator.getColor();

        Gui.drawRect(barX, barY, barX + 16, barY + 2, 0x88000000);
        Gui.drawRect(barX, barY, barX + fill, barY + 2, color);
        if (fill > 0 && fill < 16) {
            Gui.drawRect(barX + fill - 1, barY, barX + fill, barY + 2, 0xFFFFFFFF);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || mc.isGamePaused()) return;

        UUID pid = mc.player.getUniqueID();
        ItemStack held = mc.player.getHeldItemMainhand();
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(held);

        if (attrs == null || attrs.idleAnimation == null) {
            AnimationController.INSTANCE.clearIdle(pid);
            return;
        }
        if (AnimationController.INSTANCE.isPlaying(pid)) return;

        AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attrs.idleAnimation);
        if (anim == null) return;
        AnimationController.INSTANCE.playIdleIfInactive(pid, anim);
    }
}
