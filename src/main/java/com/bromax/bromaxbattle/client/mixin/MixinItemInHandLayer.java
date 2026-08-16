package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Injects WEAPON_HAND bone rotation into ItemInHandLayer.renderArmWithItem.
 *
 * In 1.20.1, renderArmWithItem(LivingEntity, ItemStack, ItemDisplayContext, HumanoidArm,
 * PoseStack, MultiBufferSource, int) is called once per hand per render frame
 * (ItemDisplayContext replaced ItemTransforms.TransformType in 1.19.4).
 * We push a pose rotation onto the PoseStack before the item is rendered so the
 * item pivots from the grip point as if the wrist twisted.
 *
 * Injection is at HEAD so the rotation is on the stack before the item render call.
 * The caller pops its own push after we exit, so the PoseStack stays balanced.
 */
@Mixin(ItemInHandLayer.class)
public class MixinItemInHandLayer {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void bhb_applyWeaponHandRotation(
            LivingEntity livingEntity,
            ItemStack stack,
            ItemDisplayContext displayContext,
            HumanoidArm arm,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int combinedLight,
            CallbackInfo ci) {

        if (!(livingEntity instanceof AbstractClientPlayer player)) return;
        UUID id = player.getUUID();
        boolean mainhand = (arm == player.getMainArm());
        float[] angles = AnimationController.INSTANCE.getWeaponAngles(id, mainhand, 1.0f);
        if (angles == null) return;

        // Apply wrist rotation around each axis if non-trivial (angles are radians)
        if (Math.abs(angles[0]) > 0.001f) poseStack.mulPose(Axis.XP.rotation(angles[0]));
        if (Math.abs(angles[1]) > 0.001f) poseStack.mulPose(Axis.YP.rotation(angles[1]));
        if (Math.abs(angles[2]) > 0.001f) poseStack.mulPose(Axis.ZP.rotation(angles[2]));
    }
}
