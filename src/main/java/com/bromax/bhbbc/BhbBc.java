package com.bromax.bhbbc;

import net.bettercombat.api.CombatFlags;
import net.bettercombat.logic.PlayerAttackProperties;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("bhb_bc")
public class BhbBc {
    public static final Logger LOGGER = LogManager.getLogger("bhb_bc");

    public BhbBc(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer sp) {
            disableBC(sp);
        }
    }

    // Re-check each server tick — BC resets comboCount internally which can restore flags.
    @SubscribeEvent
    public void onPlayerTickPost(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer sp && !player.level().isClientSide()) {
            if (!CombatFlags.isAttackDisabled(sp)) {
                disableBC(sp);
            }
        }
    }

    private static void disableBC(ServerPlayer sp) {
        CombatFlags.setAttacksDisabled(sp, true);
        // Also reset comboCount so BC's dual-wield redirect falls through to vanilla
        if (sp instanceof PlayerAttackProperties pap) {
            pap.setComboCount(-1);
        }
    }
}
