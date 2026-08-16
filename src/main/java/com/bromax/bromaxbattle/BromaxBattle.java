package com.bromax.bromaxbattle;

import com.bromax.bromaxbattle.client.ClientSetup;
import com.bromax.bromaxbattle.combat.CombatHandler;
import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(BromaxBattle.MOD_ID)
public class BromaxBattle {
    public static final String MOD_ID = "bromax_battle";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BromaxBattle() {
        BromaxBattleConfig.register(FMLJavaModLoadingContext.get().getModEventBus());

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientSetup::register);

        CombatHandler handler = new CombatHandler();
        CombatHandler.INSTANCE = handler;
        MinecraftForge.EVENT_BUS.register(handler);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(WeaponRegistry.INSTANCE::init);
    }
}
