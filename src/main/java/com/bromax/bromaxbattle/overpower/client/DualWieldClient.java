package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.animation.AnimationRegistry;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxbattle.overpower.dualwield.DualWield;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Right-click with a BHB weapon in the off hand attacks with it. Vanilla gets the click first for
 * anything the main hand or the looked-at block uses (bows, food, chests, villagers): this only
 * runs when the click falls through to the off hand.
 */
@OnlyIn(Dist.CLIENT)
public class DualWieldClient {
    private long readyAt;

    @SubscribeEvent
    public void onInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.OFF_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !DualWield.canDualWield(player)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        long now = mc.level.getGameTime();
        if (now < readyAt) return;
        readyAt = now + DualWield.lockTicks(player.getOffhandItem());

        int targetId = -1;
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) targetId = ehr.getEntity().getId();
        playOffhandAnimation(player);
        PacketDistributor.sendToServer(new OpNetwork.OffhandAttack(targetId));
    }

    /** Other players' off-hand swings. */
    public static void remoteSwing(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e instanceof Player p && p != mc.player) playOffhandAnimation(p);
    }

    private static void playOffhandAnimation(Player player) {
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(player.getOffhandItem());
        if (attrs == null || attrs.attacks.isEmpty()) return;
        AnimationDefinition anim = AnimationRegistry.INSTANCE.get(attrs.attacks.get(0).animation);
        if (anim != null) AnimationController.INSTANCE.playOffhand(player.getUUID(), anim, attrs.attacks.get(0).speedMultiplier);
    }
}
