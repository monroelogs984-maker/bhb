package com.bromax.bromaxbattle;

import com.bromax.bromaxbattle.combat.CombatHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {
    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new CombatHandler());
    }
}
