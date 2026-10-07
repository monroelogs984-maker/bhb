package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Applies arm animation to the first-person hand render.
 *
 * ItemInHandRenderer.renderArmWithItem positions and renders one hand in first-person.
 * Injecting at HEAD lets us rotate the PoseStack before vanilla applies its
 * arm positioning, so the animation acts as a pre-rotation on the whole arm.
 *
 * rx convention in first-person camera space is opposite to third-person model space —
 * no sign flip needed here (the JSON values work directly).
 */
@Mixin(ItemInHandRenderer.class)
public class MixinItemInHandRenderer {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void bhb_applyFirstPersonArmAnimation(
            AbstractClientPlayer player,
            float partialTick,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack stack,
            float equipProgress,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int combinedLight,
            CallbackInfo ci) {

        boolean mainhand = (hand == InteractionHand.MAIN_HAND);
        UUID id = player.getUUID();
        // The guard has its own first-person placement; its third-person arm angles would swing
        // the weapon out of view
        if (mainhand && com.bromax.bromaxbattle.client.GuardClient.isGuardAnimation(
                AnimationController.INSTANCE.currentAnimation(id, false))) {
            com.bromax.bromaxbattle.client.GuardClient.applyFirstPerson(poseStack, partialTick);
            return;
        }
        float[] angles = AnimationController.INSTANCE.getArmAngles(id, mainhand, partialTick);
        if (angles == null) return;

        if (Math.abs(angles[0]) > 0.001f) poseStack.mulPose(Axis.XP.rotation(angles[0]));
        if (Math.abs(angles[1]) > 0.001f) poseStack.mulPose(Axis.YP.rotation(angles[1]));
        if (Math.abs(angles[2]) > 0.001f) poseStack.mulPose(Axis.ZP.rotation(angles[2]));
    }
}
