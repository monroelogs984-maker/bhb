package com.bromax.bromaxbattle.overpower.client;

import com.bromax.bromaxbattle.weapon.AttackDefinition;
import com.bromax.bromaxbattle.weapon.WeaponAttributes;
import com.bromax.bromaxbattle.weapon.WeaponCategory;
import com.bromax.bromaxbattle.weapon.WeaponRegistry;
import com.bromax.bromaxbattle.overpower.dualwield.DualWield;
import com.bromax.bromaxbattle.overpower.profile.ProfileRegistry;
import com.bromax.bromaxbattle.overpower.profile.WeaponProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The Weaponsmith's Bench examine view: everything BHB and Overpower know about a weapon. */
@OnlyIn(Dist.CLIENT)
public class BenchScreen extends Screen {
    private final ItemStack stack;
    private final List<Component> lines = new ArrayList<>();

    public static void open(ItemStack stack) {
        Minecraft.getInstance().setScreen(new BenchScreen(stack.copy()));
    }

    private BenchScreen(ItemStack stack) {
        super(Component.translatable("screen.bromax_battle.bench"));
        this.stack = stack;
    }

    @Override
    protected void init() {
        lines.clear();
        WeaponAttributes attrs = WeaponRegistry.INSTANCE.getAttributes(stack);
        if (attrs == null) return;
        WeaponCategory cat = attrs.category;
        String catName = cat.name().toLowerCase(Locale.ROOT);
        lines.add(Component.translatable("screen.bromax_battle.bench.category",
                Component.translatable("category.bromax_battle." + catName).withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("screen.bromax_battle.bench.category_desc." + catName).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        lines.add(Component.translatable(cat.isTwoHanded() ? "screen.bromax_battle.bench.two_handed" : "screen.bromax_battle.bench.one_handed")
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(String.format(Locale.ROOT, "Attack damage %.1f   Attack speed %.2f   (%d ticks per full swing)",
                attackDamage(stack), attackSpeed(stack), DualWield.lockTicks(stack))).withStyle(ChatFormatting.GRAY));
        lines.add(Component.empty());

        WeaponProfile p = ProfileRegistry.get(cat);
        lines.add(Component.translatable("tooltip.bromax_battle.header").withStyle(ChatFormatting.DARK_RED));
        lines.add(OverpowerTooltip.statLine("overpower", p.overpower(), ChatFormatting.RED));
        lines.add(OverpowerTooltip.statLine("glare", p.glare(), ChatFormatting.GOLD));
        float pct = (p.damageMultiplier(ProfileRegistry.damagePerLevel()) - 1f) * 100f;
        lines.add(Component.translatable("tooltip.bromax_battle.damage",
                Component.literal(Math.abs(pct) < 0.05f ? "±0%" : String.format(Locale.ROOT, "%+.1f%%", pct))).withStyle(ChatFormatting.GRAY));
        lines.add(Component.empty());

        lines.add(Component.translatable("screen.bromax_battle.bench.attacks").withStyle(ChatFormatting.YELLOW));
        for (AttackDefinition a : attrs.attacks) {
            String kind = a.speedMultiplier < 0.99f ? "heavy" : a.speedMultiplier > 1.01f ? "light" : "default";
            ChatFormatting color = kind.equals("heavy") ? ChatFormatting.RED : kind.equals("light") ? ChatFormatting.AQUA : ChatFormatting.WHITE;
            lines.add(Component.translatable("screen.bromax_battle.bench.attack",
                    Component.translatable("screen.bromax_battle.bench.kind." + kind).withStyle(color),
                    Math.round(100f * a.weight / attrs.totalWeight),
                    String.format(Locale.ROOT, "%.2f", a.speedMultiplier),
                    String.format(Locale.ROOT, "%.2f", a.damageMultiplier),
                    a.hitDelay).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.empty());

        // The item's own tooltip carries mod traits (BMM etc.)
        Minecraft mc = Minecraft.getInstance();
        List<Component> tip = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
        if (tip.size() > 1) {
            lines.add(Component.translatable("screen.bromax_battle.bench.details").withStyle(ChatFormatting.YELLOW));
            for (int i = 1; i < tip.size() && i < 14; i++) lines.add(tip.get(i));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int panelW = Math.min(width - 20, 330);
        int lineH = 10;
        int panelH = Math.min(height - 20, 44 + lines.size() * lineH);
        int x = (width - panelW) / 2, y = (height - panelH) / 2;
        g.fill(x, y, x + panelW, y + panelH, 0xE0101014);
        g.renderOutline(x, y, panelW, panelH, 0xFF8A6A3A);
        g.pose().pushPose();
        g.pose().translate(x + 10, y + 8, 0);
        g.pose().scale(2f, 2f, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
        g.drawString(font, stack.getHoverName(), x + 48, y + 12, 0xFFFFFF);
        g.drawString(font, title, x + 48, y + 24, 0x8A6A3A, false);
        int ly = y + 42;
        for (Component line : lines) {
            if (ly + lineH > y + panelH) break;
            g.drawString(font, line, x + 10, ly, 0xFFFFFF, false);
            ly += lineH;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static float attackDamage(ItemStack stack) {
        float[] v = {1f};
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (attr.equals(Attributes.ATTACK_DAMAGE) && mod.operation() == AttributeModifier.Operation.ADD_VALUE) v[0] += (float) mod.amount();
        });
        return v[0];
    }

    private static float attackSpeed(ItemStack stack) {
        float[] v = {4f};
        stack.forEachModifier(EquipmentSlotGroup.MAINHAND, (attr, mod) -> {
            if (attr.equals(Attributes.ATTACK_SPEED) && mod.operation() == AttributeModifier.Operation.ADD_VALUE) v[0] += (float) mod.amount();
        });
        return v[0];
    }
}
