package com.bromax.bromaxbattle.overpower.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@OnlyIn(Dist.CLIENT)
public final class OpClient {
    private OpClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(GlareAnimations::load));
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
                e.registerBlockEntityRenderer(com.bromax.bromaxbattle.overpower.registry.OpRegistries.BENCH_BE.get(), BenchRenderer::new));
        NeoForge.EVENT_BUS.register(new OverpowerTooltip());
        NeoForge.EVENT_BUS.register(new OverpowerHud());
        NeoForge.EVENT_BUS.register(new GlareAnimations());
        NeoForge.EVENT_BUS.register(new DualWieldClient());
    }
}
