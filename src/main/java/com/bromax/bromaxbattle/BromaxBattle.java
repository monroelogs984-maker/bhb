package com.bromax.bromaxbattle;

import com.bromax.bromaxbattle.config.BromaxBattleConfig;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(modid = BromaxBattle.MOD_ID, name = BromaxBattle.MOD_NAME, version = BromaxBattle.VERSION,
        dependencies = "required-after:bromaxlib")
public class BromaxBattle {
    public static final String MOD_ID = "bromax_battle";
    public static final String MOD_NAME = "BROMAX's Haphazard Battle";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @SidedProxy(
        clientSide = "com.bromax.bromaxbattle.client.ClientProxy",
        serverSide = "com.bromax.bromaxbattle.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configFile = new File(event.getModConfigurationDirectory(), "bromax_battle.cfg");
        BromaxBattleConfig.load(configFile);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        WeaponRegistry.INSTANCE.init();
    }
}
