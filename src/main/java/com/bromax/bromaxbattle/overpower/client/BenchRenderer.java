package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.overpower.bench.BenchBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Lays the displayed weapon flat on the bench top, along the bench's length. */
@OnlyIn(Dist.CLIENT)
public class BenchRenderer implements BlockEntityRenderer<BenchBlockEntity> {
    public BenchRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BenchBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        ItemStack stack = be.getItem();
        if (stack.isEmpty()) return;
        float yaw = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING).toYRot();
        pose.pushPose();
        pose.translate(0.5, 14.2 / 16.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.XP.rotationDegrees(90));
        pose.mulPose(Axis.ZP.rotationDegrees(45));
        pose.scale(0.75f, 0.75f, 0.75f);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
        pose.popPose();
    }
}
