package com.bromax.bromaxbattle;

import com.bromax.bromaxbattle.client.ClientSetup;
import com.bromax.bromaxbattle.combat.CombatHandler;
import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

@Mod(BromaxBattle.MOD_ID)
public class BromaxBattle {
    public static final String MOD_ID = "bromax_battle";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BromaxBattle() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BromaxBattleConfig.register();

        modBus.addListener(this::commonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.register(modBus);
        }

        CombatHandler handler = new CombatHandler();
        CombatHandler.INSTANCE = handler;
        MinecraftForge.EVENT_BUS.register(handler);
        com.bromax.bromaxbattle.combat.DamageProbe.register();
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(WeaponRegistry.INSTANCE::init);
    }
}
