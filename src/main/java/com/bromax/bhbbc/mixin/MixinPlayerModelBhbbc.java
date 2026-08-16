package com.bromax.bhbbc.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Redirects the applyToModel call that BHB's MixinPlayerModel injects into
 * setupAnim at RETURN, suppressing body/arm ModelPart rotation entirely.
 *
 * This is the belt-and-suspenders cover: even if MixinAnimationController's
 * play() cancel works (leaving no state), this ensures applyToModel is a no-op
 * regardless of AnimationController state.
 *
 * priority = 2000 applies after BHB so the INVOKE is already present.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = PlayerModel.class, priority = 2000)
public class MixinPlayerModelBhbbc {

    @Redirect(
        method = "setupAnim",
        at = @At(value = "INVOKE",
                 target = "Lcom/bromax/bromaxbattle/animation/AnimationController;applyToModel(Ljava/util/UUID;Lnet/minecraft/client/model/HumanoidModel;F)V",
                 remap = false)
    )
    private void bhbbc_suppressApplyToModel(AnimationController instance, UUID id, HumanoidModel<?> model, float partialTick) {
        // no-op: BC's animations write directly to ModelParts via PlayerAnimationAPI
    }
}
