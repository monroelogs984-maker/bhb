package com.bromax.bromaxbattle;

import com.bromax.bromaxbattle.client.ClientSetup;
import com.bromax.bromaxbattle.combat.CombatHandler;
import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(BromaxBattle.MOD_ID)
public class BromaxBattle {
    public static final String MOD_ID = "bromax_battle";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BromaxBattle(IEventBus modBus, ModContainer container) {
        BromaxBattleConfig.register(container);

        modBus.addListener(this::commonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.register(modBus);
        }

        CombatHandler handler = new CombatHandler();
        CombatHandler.INSTANCE = handler;
        NeoForge.EVENT_BUS.register(handler);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(WeaponRegistry.INSTANCE::init);
    }
}
