package com.bromax.bromaxbattle.client.mixin;

import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes BHB weapons block when right-clicked.
 *
 * Three injections work together:
 *  1. use() → returns CONSUME so vanilla calls startUsingItem on the server,
 *     which sets isUsingItem() to true and begins the block state.
 *  2. getUseAnimation() → returns BLOCK so the first-person arm raises and
 *     vanilla's Player.isBlocking() check passes.
 *  3. getUseDuration() → returns 72000 (same as shields) so isBlocking()
 *     stays true for the full hold duration.
 *
 * Attacking cancels the block via player.stopUsingItem() in CombatHandler.
 * A 3-second item cooldown is applied after a block lands (also in CombatHandler).
 */
@Mixin(Item.class)
public class MixinItemBhbBlock {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void bhb_use(Level level, Player player, InteractionHand hand,
                         CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (hand != InteractionHand.MAIN_HAND) return;
        ItemStack stack = player.getItemInHand(hand);
        if (WeaponRegistry.INSTANCE.getAttributes(stack) == null) return;
        if (player.getCooldowns().isOnCooldown(stack.getItem())) return;
        // Let offhand consumables (food, potions, honey, etc.) take priority over blocking
        ItemStack offhand = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
        UseAnim offAnim = offhand.getItem().getUseAnimation(offhand);
        if (offAnim == UseAnim.EAT || offAnim == UseAnim.DRINK) return;
        cir.setReturnValue(InteractionResultHolder.consume(stack));
    }

    @Inject(method = "getUseAnimation", at = @At("RETURN"), cancellable = true)
    private void bhb_getUseAnimation(ItemStack stack, CallbackInfoReturnable<UseAnim> cir) {
        if (cir.getReturnValue() != UseAnim.NONE) return;
        if (WeaponRegistry.INSTANCE.getAttributes(stack) != null) {
            cir.setReturnValue(UseAnim.BLOCK);
        }
    }

    @Inject(method = "getUseDuration", at = @At("RETURN"), cancellable = true)
    private void bhb_getUseDuration(ItemStack stack, LivingEntity entity,
                                    CallbackInfoReturnable<Integer> cir) {
        if (WeaponRegistry.INSTANCE.getAttributes(stack) != null) {
            cir.setReturnValue(72000);
        }
    }
}
