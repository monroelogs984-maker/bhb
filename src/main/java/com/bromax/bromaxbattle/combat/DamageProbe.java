package com.bromax.bromaxbattle.combat;

import com.bromax.bromaxbattle.BromaxBattle;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dev-only damage harness. {@code /bhbdmgtest <attacks> [item]} equips the item, spawns an
 * armorless, AI-less cow with a huge health pool in front of the player, attacks it through
 * the real attackTargetEntityWithCurrentItem path every time the attack lock allows, and reports
 * the damage each hit was meant to deal against the health it actually removed.
 *
 * Registered only when the JVM runs with -Dbhb.debug=true.
 */
public final class DamageProbe {
    private static EntityPlayerMP player;
    private static EntityCow target;
    private static int remaining;
    private static int remainingTarget;
    private static int ticksSpent;
    private static int waitTicks;
    private static float lastHealth;
    private static final List<float[]> hits = new ArrayList<>(); // {incoming, applied, hurtResistantTime, worldTime}
    private static float expected;

    private static boolean enabled() {
        return Boolean.getBoolean("bhb.debug");
    }

    public static void register() {
        if (!enabled()) return;
        MinecraftForge.EVENT_BUS.register(new DamageProbe());
    }

    public static void registerCommand(FMLServerStartingEvent event) {
        if (!enabled()) return;
        event.registerServerCommand(new CommandBase() {
            @Override public String getName() { return "bhbdmgtest"; }
            @Override public String getUsage(ICommandSender sender) { return "/bhbdmgtest <attacks> [item]"; }
            @Override public int getRequiredPermissionLevel() { return 2; }
            @Override
            public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
                if (args.length < 1) throw new CommandException(getUsage(sender));
                int attacks = parseInt(args[0], 1, 500);
                String item = args.length > 1 ? args[1] : "minecraft:iron_sword";
                start(getCommandSenderAsPlayer(sender), attacks, item);
            }
        });
    }

    private static void start(EntityPlayerMP p, int attacks, String itemId) {
        Item item = Item.getByNameOrId(itemId);
        if (item == null) return;
        p.setItemStackToSlot(EntityEquipmentSlot.MAINHAND, new ItemStack(item));
        p.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, ItemStack.EMPTY);
        WorldServer world = p.getServerWorld();
        if (target != null) target.setDead();
        target = new EntityCow(world); // passive, so Peaceful doesn't remove it
        target.setNoAI(true);
        target.setSilent(true);
        target.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(1024);
        target.getEntityAttribute(SharedMonsterAttributes.ARMOR).setBaseValue(0);
        target.getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        target.setHealth(1024);
        double yaw = Math.toRadians(p.rotationYaw);
        target.setLocationAndAngles(p.posX - Math.sin(yaw) * 2.0, p.posY, p.posZ + Math.cos(yaw) * 2.0, 0f, 0f);
        world.spawnEntity(target);
        player = p;
        remaining = attacks;
        remainingTarget = attacks;
        ticksSpent = 0;
        waitTicks = 20;
        hits.clear();
        lastHealth = target.getHealth();
        p.sendMessage(new TextComponentString("[BHB] damage test: " + attacks + " attacks with " + itemId));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (player == null || target == null) return;
        if (waitTicks-- > 0) return;
        if (remaining > 0) {
            // item attribute modifiers apply a tick after equipping, so read it here
            expected = (float) player.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue();
            // Click every tick; BHB's lock refuses early clicks, so count only hits that landed
            player.attackTargetEntityWithCurrentItem(target);
            player.swingArm(EnumHand.MAIN_HAND);
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
        if (event.getEntityLiving() != target) return;
        hits.add(new float[]{event.getAmount(), -1f, target.hurtResistantTime, target.world.getTotalWorldTime()});
    }

    @SubscribeEvent
    public void onDamage(LivingDamageEvent event) {
        if (event.getEntityLiving() != target || hits.isEmpty()) return;
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
        player.sendMessage(new TextComponentString(summary));
        target.setDead();
        target = null;
        player = null;
    }
}
