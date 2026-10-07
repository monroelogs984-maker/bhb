package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Injects grip + WEAPON_HAND bone rotation into ItemInHandLayer.renderArmWithItem.
 *
 * Injection point is right before the ItemInHandRenderer.renderItem call, AFTER
 * vanilla's translate-to-hand and base rotations — so the PoseStack origin sits
 * at the item itself and rotations pivot there instead of at the model root
 * (a HEAD injection made large rotations swing the item out of the hand).
 * A small pivot shift toward the grip end keeps long weapons anchored in the
 * fist while they turn. The caller pops its own push after renderItem, so the
 * PoseStack stays balanced.
 */
@Mixin(ItemInHandLayer.class)
public class MixinItemInHandLayer {

    // Item-frame offset from the render origin toward the grip end of the blade
    private static final float GRIP_PIVOT = 0.18f;

    @Inject(method = "renderArmWithItem",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemTransforms$TransformType;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void bhb_applyWeaponHandRotation(
            LivingEntity livingEntity,
            ItemStack stack,
            ItemTransforms.TransformType displayContext,
            HumanoidArm arm,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int combinedLight,
            CallbackInfo ci) {

        if (!(livingEntity instanceof AbstractClientPlayer player)) return;
        UUID id = player.getUUID();
        boolean mainhand = (arm == player.getMainArm());

        com.bromax.bromaxbattle.animation.Quat grip = AnimationController.INSTANCE.getGripRotation(id, mainhand);
        float[] angles = AnimationController.INSTANCE.getWeaponAngles(
                id, mainhand, AnimationController.INSTANCE.getPartialTick(id));
        if (grip == null && angles == null) return;

        // Pivot at the grip end of the blade so the weapon turns in the fist
        poseStack.translate(-GRIP_PIVOT, -GRIP_PIVOT, 0f);
        if (grip != null) poseStack.mulPose(grip.toMojang());
        if (angles != null) {
            if (Math.abs(angles[0]) > 0.001f) poseStack.mulPose(Vector3f.XP.rotation(angles[0]));
            if (Math.abs(angles[1]) > 0.001f) poseStack.mulPose(Vector3f.YP.rotation(angles[1]));
            if (Math.abs(angles[2]) > 0.001f) poseStack.mulPose(Vector3f.ZP.rotation(angles[2]));
        }
        poseStack.translate(GRIP_PIVOT, GRIP_PIVOT, 0f);
    }
}
