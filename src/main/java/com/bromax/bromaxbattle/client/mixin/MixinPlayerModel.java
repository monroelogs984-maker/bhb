package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires after PlayerModel.setupAnim so vanilla arm poses are already set.
 * Our rotation values overwrite them, taking priority.
 *
 * Targets PlayerModel directly (not HumanoidModel) so this only fires for
 * players — not for zombies/skeletons that also extend HumanoidModel.
 *
 * partialTick is derived from ageInTicks % 1.0f (same technique as Better Combat).
 * ageInTicks = player.tickCount + partialTick, so the fractional part ≈ partialTick.
 */
@Mixin(PlayerModel.class)
public class MixinPlayerModel {

    @Inject(method = "setupAnim", at = @At("RETURN"))
    private void bhb_applyAnimation(LivingEntity entity,
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, CallbackInfo ci) {

        if (!(entity instanceof AbstractClientPlayer player)) return;
        if (!AnimationController.INSTANCE.hasActiveState(player.getUUID())) return;
        float partialTick = ageInTicks % 1.0f;
        AnimationController.INSTANCE.applyToModel(player.getUUID(), (PlayerModel<?>) (Object) this, partialTick);
    }
}
