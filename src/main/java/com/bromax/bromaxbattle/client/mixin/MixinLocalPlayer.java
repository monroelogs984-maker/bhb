package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scales movement input and fires forward velocity impulse during attack animations.
 *
 * In 1.19.2, LocalPlayer.tick() processes movement input into this.xxa (strafe)
 * and this.zza (forward) before calling super. We inject at TAIL after these are
 * set and scale them down during the swing.
 *
 * The lunge impulse adds a forward velocity push at the first tick of each attack
 * via addDeltaMovement, matching the 1.12.2 motionX/motionZ approach.
 */
@Mixin(LocalPlayer.class)
public class MixinLocalPlayer {

    @Inject(method = "tick", at = @At("TAIL"))
    private void bhb_swingMovement(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!AnimationController.INSTANCE.isPlaying(self.getUUID())) return;

        // Soft movement restriction during swing
        float progress = AnimationController.INSTANCE.getAttackSwingProgress(self.getUUID());
        float factor   = swingMovementFactor(progress);
        self.xxa *= factor;
        self.zza *= factor;

        // One-shot forward lunge impulse at attack start
        float lungeImpulse = AnimationController.INSTANCE.consumeLungeImpulse(self.getUUID());
        if (lungeImpulse > 0f) {
            float speed  = lungeImpulse * AnimationController.LUNGE_VELOCITY_SCALE;
            double yawRad = Math.toRadians(self.getYRot());
            self.setDeltaMovement(self.getDeltaMovement().add(
                -Math.sin(yawRad) * speed,
                0.0,
                 Math.cos(yawRad) * speed
            ));
        }
    }

    // 1.0 at rest, 0.85 at peak swing, back to 1.0 at recovery
    private static float swingMovementFactor(float progress) {
        if (progress <= 0f || progress >= 1f) return 1.0f;
        float blend = progress < 0.5f
            ? easeOutCubic(progress * 2f)
            : easeOutCubic((1f - progress) * 2f);
        return 1.0f - (1.0f - 0.85f) * blend;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
