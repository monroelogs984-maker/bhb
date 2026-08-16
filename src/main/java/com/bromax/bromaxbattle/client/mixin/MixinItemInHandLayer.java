package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Injects WEAPON_HAND bone rotation into ItemInHandLayer.renderArmWithItem.
 *
 * In 1.19.2, ItemInHandLayer.renderArmWithItem(AbstractClientPlayer, HumanoidArm, ItemStack,
 * PoseStack, MultiBufferSource, int) is called once per hand per render frame.
 * We push a pose rotation onto the PoseStack before the item is rendered so the
 * item pivots from the grip point as if the wrist twisted.
 *
 * Injection is at HEAD so the rotation is on the stack before the item render call.
 * At RETURN we would need to explicitly pop, which is error-prone. Instead we
 * push at HEAD, render happens, then the caller pops its own push — the PoseStack
 * is balanced by the existing code after we exit.
 *
 * NOTE: Verify the exact method name against decompiled 1.19.2 sources.
 * Candidate: "renderArmWithItem" or the private helper called inside render().
 */
@Mixin(ItemInHandLayer.class)
public class MixinItemInHandLayer {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void bhb_applyWeaponHandRotation(
            LivingEntity livingEntity,
            ItemStack stack,
            ItemTransforms.TransformType transformType,
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
        if (Math.abs(angles[0]) > 0.001f) {
            poseStack.mulPose(new Quaternion(Vector3f.XP, angles[0], false));
        }
        if (Math.abs(angles[1]) > 0.001f) {
            poseStack.mulPose(new Quaternion(Vector3f.YP, angles[1], false));
        }
        if (Math.abs(angles[2]) > 0.001f) {
            poseStack.mulPose(new Quaternion(Vector3f.ZP, angles[2], false));
        }
    }
}
