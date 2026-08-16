package com.bromax.bhbbc.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Redirects the AnimationController calls that BHB's MixinItemInHandLayer
 * injects into renderArmWithItem, forcing both to return null so BHB's own
 * "if (grip == null && angles == null) return;" guard fires and no rotation applies.
 *
 * priority = 2000 ensures this mixin applies after BHB (default 1000), so the
 * INVOKE instructions injected by BHB are already present and can be redirected.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = ItemInHandLayer.class, priority = 2000)
public class MixinItemInHandLayerBhbbc {

    @Redirect(
        method = "renderArmWithItem",
        at = @At(value = "INVOKE",
                 target = "Lcom/bromax/bromaxbattle/animation/AnimationController;getGripRotation(Ljava/util/UUID;Z)Lorg/joml/Quaternionf;",
                 remap = false)
    )
    private Quaternionf bhbbc_nullGrip(AnimationController instance, UUID id, boolean mainhand) {
        return null;
    }

    @Redirect(
        method = "renderArmWithItem",
        at = @At(value = "INVOKE",
                 target = "Lcom/bromax/bromaxbattle/animation/AnimationController;getWeaponAngles(Ljava/util/UUID;ZF)[F",
                 remap = false)
    )
    private float[] bhbbc_nullWeaponAngles(AnimationController instance, UUID id, boolean mainhand, float partialTick) {
        return null;
    }
}
