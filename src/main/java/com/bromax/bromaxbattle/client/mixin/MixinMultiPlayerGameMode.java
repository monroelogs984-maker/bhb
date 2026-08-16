package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.combat.CombatHandler;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Inject(method = "attack", at = @At("HEAD"))
    private void bhb_onClientAttack(Player player, Entity target, CallbackInfo ci) {
        if (CombatHandler.INSTANCE == null) return;
        CombatHandler.INSTANCE.clientAttack(player);
    }
}
