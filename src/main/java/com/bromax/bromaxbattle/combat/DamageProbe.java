package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.BromaxBattle;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dev-only damage harness. {@code /bhbdmgtest <attacks> [item]} equips the item, spawns an
 * armorless, AI-less cow with a huge health pool in front of the player, attacks it through
 * the real Player.attack path every time the attack lock allows, and reports the damage each
 * hit was meant to deal against the health it actually removed.
 *
 * Registered only when the JVM runs with -Dbhb.debug=true.
 */
public final class DamageProbe {
    private static ServerPlayer player;
    private static Cow target;
    private static int remaining;
    private static int remainingTarget;
    private static int ticksSpent;
    private static int waitTicks;
    private static float lastHealth;
    private static final List<float[]> hits = new ArrayList<>(); // {incoming, applied, invulnerableTime}
    private static float expected;

    public static void register() {
        if (!Boolean.getBoolean("bhb.debug")) return;
        MinecraftForge.EVENT_BUS.register(new DamageProbe());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bhbdmgtest")
                .then(Commands.argument("attacks", IntegerArgumentType.integer(1, 500))
                        .executes(ctx -> start(ctx.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(ctx, "attacks"), "minecraft:iron_sword"))
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> start(ctx.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(ctx, "attacks"),
                                        StringArgumentType.getString(ctx, "item"))))));
    }

    private static int start(ServerPlayer p, int attacks, String itemId) {
        Item item = Registry.ITEM.get(new ResourceLocation(itemId));
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        p.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        ServerLevel level = p.getLevel();
        if (target != null) target.discard();
        target = EntityType.COW.create(level); // passive, so Peaceful doesn't remove it
        if (target == null) return 0;
        target.setNoAi(true);
        target.setSilent(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        target.setHealth(1024);
        double yaw = Math.toRadians(p.getYRot());
        target.moveTo(p.getX() - Math.sin(yaw) * 2.0, p.getY(), p.getZ() + Math.cos(yaw) * 2.0, 0f, 0f);
        level.addFreshEntity(target);
        player = p;
        remaining = attacks;
        remainingTarget = attacks;
        ticksSpent = 0;
        waitTicks = 20;
        hits.clear();
        lastHealth = target.getHealth();
        p.sendSystemMessage(Component.literal("[BHB] damage test: " + attacks + " attacks with " + itemId));
        return 1;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return; // NeoForge fires .Post only
        if (player == null || target == null) return;
        if (waitTicks-- > 0) return;
        if (remaining > 0) {
            // item attribute modifiers apply a tick after equipping, so read it here
            expected = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
            // Click every tick; BHB's lock refuses early clicks, so count only hits that landed
            player.attack(target);
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (hits.size() >= remainingTarget || ++ticksSpent > remainingTarget * 60) {
                remaining = 0;
                waitTicks = 40; // let delayed hits land
            }
            return;
        }
        report();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onIncoming(LivingHurtEvent event) {
        if (event.getEntity() != target) return;
        hits.add(new float[]{event.getAmount(), -1f, target.invulnerableTime, target.level.getGameTime()});
    }

    @SubscribeEvent
    public void onDamagePost(LivingDamageEvent event) {
        if (event.getEntity() != target || hits.isEmpty()) return;
        hits.get(hits.size() - 1)[1] = event.getAmount();
    }

    private static void report() {
        float totalIncoming = 0, totalApplied = 0;
        int reduced = 0, blocked = 0;
        StringBuilder sb = new StringBuilder();
        StringBuilder gaps = new StringBuilder();
        for (int i = 1; i < hits.size(); i++) gaps.append(' ').append((int) (hits.get(i)[3] - hits.get(i - 1)[3]));
        for (float[] h : hits) {
            totalIncoming += h[0];
            if (h[1] < 0) { blocked++; continue; }
            totalApplied += h[1];
            if (h[1] < h[0] - 0.01f) reduced++;
            sb.append(String.format(Locale.ROOT, " %.2f->%.2f(i%d)", h[0], h[1], (int) h[2]));
        }
        float lost = lastHealth - target.getHealth();
        String summary = String.format(Locale.ROOT,
                "[BHB] damage test: %d hits, attribute %.2f, avg incoming %.2f, avg applied %.2f, health lost %.1f, "
                        + "%d reduced by i-frames, %d fully blocked",
                hits.size(), expected, hits.isEmpty() ? 0 : totalIncoming / hits.size(),
                hits.isEmpty() ? 0 : totalApplied / hits.size(), lost, reduced, blocked);
        BromaxBattle.LOGGER.info(summary);
        BromaxBattle.LOGGER.info("[BHB] damage test hits:{}", sb);
        BromaxBattle.LOGGER.info("[BHB] damage test tick gaps:{}", gaps);
        player.sendSystemMessage(Component.literal(summary));
        target.discard();
        target = null;
        player = null;
    }
}
