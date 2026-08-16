package com.bromax.bhbbc.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = "bhb_bc", dist = Dist.CLIENT)
public class BhbBcClient {
    public BhbBcClient(IEventBus modEventBus) {
        // Client-side init — animation bridge loads its mapping on first use via static initializer
    }
}
