package com.bromax.bhbbc.mixin;

import com.bromax.bromaxbattle.combat.CombatHandler;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponClassifier;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CombatHandler.class, remap = false)
public class MixinCombatHandler {

    // Prevent animation trigger for non-weapons (bare hand always passes)
    @Inject(method = "triggerAnimation", at = @At("HEAD"), cancellable = true, remap = false)
    private void bhbbc_guardClientAnim(Player player, boolean dualWieldTurn, CallbackInfo ci) {
        if (!isBhbWeapon(player.getMainHandItem())) ci.cancel();
    }

    // Prevent hit delay, attack lock, and damage processing for non-weapons
    @Inject(method = "onAttackEntityServer", at = @At("HEAD"), cancellable = true, remap = false)
    private void bhbbc_guardServer(AttackEntityEvent event, CallbackInfo ci) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        if (!isBhbWeapon(player.getMainHandItem())) ci.cancel();
    }

    /**
     * True if BHB explicitly recognizes this item as a weapon (not just the
     * gauntlets fallback). Empty stack = bare hand = intentional gauntlets.
     * Items with no classifier confidence and GAUNTLETS category = unknown item.
     */
    private static boolean isBhbWeapon(ItemStack stack) {
        if (stack.isEmpty()) return true;
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(stack);
        if (attrs == null) return false;
        if (attrs.category != WeaponCategory.GAUNTLETS) return true;
        // Gauntlets category can be either an explicit gauntlet item or a fallback.
        // Only allow if the classifier actually matched it.
        return WeaponClassifier.classify(stack).confidence > 0;
    }
}
