package com.bromax.bromaxbattle.overpower.combat;

import com.bromax.bromaxbattle.overpower.profile.ProfileRegistry;
import com.bromax.bromaxbattle.overpower.profile.WeaponProfile;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Applies each weapon's Overpower trade-off to its melee damage: weapons invested in the
 * Overpower minigame hit slightly softer, low-investment weapons slightly harder (a few percent
 * at most). Runs after BHB's own multipliers.
 */
public class DamageTradeoff {

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        WeaponProfile profile = ProfileRegistry.of(com.bromax.bromaxbattle.overpower.dualwield.DualWield.OFFHAND_HIT.get()
                ? player.getOffhandItem() : player.getMainHandItem());
        if (profile == null) return;
        float mult = profile.damageMultiplier(ProfileRegistry.damagePerLevel());
        if (mult != 1f) event.setAmount(event.getAmount() * mult);
    }
}
