package com.bromax.bhbbc.mixin;

import com.bromax.bhbbc.client.BCAnimationBridge;
import com.bromax.bromaxbattle.animation.AnimationController;
import com.bromax.bromaxbattle.animation.AnimationDefinition;
import com.bromax.bromaxbattle.combat.CombatHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Redirects the AnimationController.play() and playOffhand() calls that live
 * inside CombatHandler.triggerAnimation(). This is the reliable path: we target
 * the call site rather than AnimationController itself, avoiding class-loading-
 * order issues with mod-to-mod direct injection.
 *
 * Because triggerAnimation is only reachable from client-side call paths
 * (clientAttack via MixinMultiPlayerGameMode, onSwingEmpty with isClientSide guard),
 * it is safe to call BCAnimationBridge here.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = CombatHandler.class, remap = false)
public class MixinCombatHandlerClient {

    // Redirects both play() calls in triggerAnimation (dual-wield main-hand and
    // single-weapon paths) to fire a BC animation instead.
    @Redirect(
        method = "triggerAnimation",
        at = @At(value = "INVOKE",
                 target = "Lcom/bromax/bromaxbattle/animation/AnimationController;play(Ljava/util/UUID;Lcom/bromax/bromaxbattle/animation/AnimationDefinition;F)V",
                 remap = false)
    )
    private void bhbbc_redirectPlay(AnimationController instance, UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        BCAnimationBridge.onBhbPlay(playerId, animation, speedMultiplier);
        // Original play() is NOT called — AnimationController state stays clear,
        // so BHB's render guards (hasActiveState, null-checks) short-circuit naturally.
    }

    // Silently no-op the off-hand animation path for now; BC off-hand support is
    // limited and dual-wield BC mapping is not yet configured.
    @Redirect(
        method = "triggerAnimation",
        at = @At(value = "INVOKE",
                 target = "Lcom/bromax/bromaxbattle/animation/AnimationController;playOffhand(Ljava/util/UUID;Lcom/bromax/bromaxbattle/animation/AnimationDefinition;F)V",
                 remap = false)
    )
    private void bhbbc_redirectPlayOffhand(AnimationController instance, UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        // no-op
    }
}
