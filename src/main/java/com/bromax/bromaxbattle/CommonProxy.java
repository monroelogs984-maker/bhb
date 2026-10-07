package com.bromax.bromaxbattle;

import com.bromax.bromaxbattle.combat.CombatHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {
    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {
        CombatHandler handler = new CombatHandler();
        CombatHandler.INSTANCE = handler;
        MinecraftForge.EVENT_BUS.register(handler);
        com.bromax.bromaxbattle.combat.DamageProbe.register();
    }
}
