package com.bromax.bhbbc.mixin;

import com.bromax.bromaxbattle.animation.AnimationController;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Redirects the getArmAngles call that BHB's MixinItemInHandRenderer injects
 * into renderArmWithItem (first-person), returning null so BHB's own null-check
 * short-circuits and no arm/weapon rotation is applied.
 *
 * priority = 2000 ensures we apply after BHB so the INVOKE is already present.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = ItemInHandRenderer.class, priority = 2000)
public class MixinItemInHandRendererBhbbc {

    @Redirect(
        method = "renderArmWithItem",
        at = @At(value = "INVOKE",
                 target = "Lcom/bromax/bromaxbattle/animation/AnimationController;getArmAngles(Ljava/util/UUID;ZF)[F",
                 remap = false)
    )
    private float[] bhbbc_nullArmAngles(AnimationController instance, UUID id, boolean mainhand, float partialTick) {
        return null;
    }
}
