package com.bromax.bromaxbattle.overpower;

import com.bromax.bromaxbattle.api.BhbApi;
import com.bromax.bromaxbattle.overpower.combat.DamageTradeoff;
import com.bromax.bromaxbattle.overpower.combat.OverpowerManager;
import com.bromax.bromaxbattle.overpower.config.OpConfig;
import com.bromax.bromaxbattle.overpower.network.OpNetwork;
import com.bromax.bromaxbattle.overpower.profile.ProfileRegistry;
import com.bromax.bromaxbattle.overpower.registry.OpRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/** Overpower (pressure bar, Glare Strike), off-hand dual wielding and the Weaponsmith's Bench. */
public final class OverpowerSetup {
    private OverpowerSetup() {}

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, OpConfig.SPEC, "bromax_battle-overpower.toml");
        OpRegistries.register(modBus);
        OpNetwork.register(modBus);
        ProfileRegistry.load();
        // Off-hand attacks are on right-click now; CombatHandler's old left-click alternation stays off
        BhbApi.setDualWieldHandledExternally(true);

        NeoForge.EVENT_BUS.register(new DamageTradeoff());
        NeoForge.EVENT_BUS.register(new OverpowerManager());
        com.bromax.bromaxbattle.overpower.debug.OpTest.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.bromax.bromaxbattle.overpower.client.OpClient.register(modBus);
            com.bromax.bromaxbattle.overpower.client.OpTestRunner.register();
        }
    }
}
