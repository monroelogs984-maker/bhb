package com.bromax.bhbbc.mixin;

import com.bromax.bhbbc.client.BCAnimationBridge;
import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
@Mixin(value = AnimationController.class, remap = false)
public class MixinAnimationController {

    // Fire BC's animation then always cancel BHB's play() so no AnimationState
    // is ever set. With no state, every downstream BHB getter (getGripRotation,
    // getWeaponAngles, getArmAngles, hasActiveState, isPlaying) returns its
    // natural null/false result and all BHB render paths short-circuit on their
    // own null-checks — no explicit suppression needed.
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, remap = false)
    private void bhbbc_interceptPlay(UUID playerId, AnimationDefinition animation, float speedMultiplier, CallbackInfo ci) {
        BCAnimationBridge.onBhbPlay(playerId, animation, speedMultiplier);
        ci.cancel();
    }

    // Cancel offhand state too — nothing should accumulate in AnimationController.
    @Inject(method = "playOffhand", at = @At("HEAD"), cancellable = true, remap = false)
    private void bhbbc_interceptPlayOffhand(UUID playerId, AnimationDefinition animation, float speedMultiplier, CallbackInfo ci) {
        ci.cancel();
    }
}
