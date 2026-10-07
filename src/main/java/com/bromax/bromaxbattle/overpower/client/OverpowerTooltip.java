package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.overpower.profile.ProfileRegistry;
import com.bromax.bromaxbattle.overpower.profile.WeaponProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.Locale;

/** Shift-expanded Overpower stats on every BHB weapon tooltip. */
@OnlyIn(Dist.CLIENT)
public class OverpowerTooltip {

    private static final String[] LEVEL_KEYS = {"very_low", "low", "standard", "good", "excellent"};

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        WeaponProfile profile = ProfileRegistry.of(event.getItemStack());
        if (profile == null) return;
        List<Component> lines = event.getToolTip();
        if (!Screen.hasShiftDown()) {
            lines.add(Component.translatable("tooltip.bromax_battle.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        lines.add(Component.translatable("tooltip.bromax_battle.header").withStyle(ChatFormatting.DARK_RED));
        lines.add(statLine("overpower", profile.overpower(), ChatFormatting.RED));
        lines.add(statLine("glare", profile.glare(), ChatFormatting.GOLD));
        float pct = (profile.damageMultiplier(ProfileRegistry.damagePerLevel()) - 1f) * 100f;
        String value = Math.abs(pct) < 0.05f ? "±0%" : String.format(Locale.ROOT, "%+.1f%%", pct);
        ChatFormatting color = pct > 0.05f ? ChatFormatting.GREEN : pct < -0.05f ? ChatFormatting.RED : ChatFormatting.GRAY;
        lines.add(Component.translatable("tooltip.bromax_battle.damage",
                Component.literal(value).withStyle(color)).withStyle(ChatFormatting.GRAY));
    }

    /** "Overpower: ■■■□□ Good" */
    public static Component statLine(String stat, int level, ChatFormatting color) {
        int filled = level + 3; // -2..+2 -> 1..5 pips
        StringBuilder pips = new StringBuilder();
        for (int i = 1; i <= 5; i++) pips.append(i <= filled ? '■' : '□');
        return Component.translatable("tooltip.bromax_battle." + stat,
                Component.literal(pips.toString()).withStyle(color),
                Component.translatable("tooltip.bromax_battle.level." + LEVEL_KEYS[level + 2]).withStyle(color))
                .withStyle(ChatFormatting.GRAY);
    }
}
