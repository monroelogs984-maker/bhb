package com.bromax.bromaxbattle.overpower.debug;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.overpower.combat.OverpowerData;
import com.bromax.bromaxbattle.overpower.combat.OverpowerManager;
import com.bromax.bromaxbattle.combat.GuardHandler;
import com.bromax.bromaxbattle.overpower.dualwield.DualWield;
import com.bromax.bromaxbattle.overpower.registry.OpRegistries;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Locale;

/**
 * Dev-only scripted Overpower test, registered with -Dbhb.optest=true.
 * {@code /optest} runs: A) the player's hits break a zombie's bar; B) the zombie's hits build the
 * player's pressure until a Glare window opens, then the player counters; C) an off-hand attack.
 * Everything is logged with the "[OpTest]" prefix.
 */
public final class OpTest {
    private static ServerPlayer player;
    private static Zombie zombie;
    private static int step;
    private static int wait;
    private static float zombieHealthBefore;

    public static void register() {
        if (!Boolean.getBoolean("bhb.optest")) return;
        NeoForge.EVENT_BUS.register(new OpTest());
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("optest").executes(ctx -> {
            start(ctx.getSource().getPlayerOrException());
            return 1;
        }).then(Commands.literal("hit").executes(ctx -> {
            frontHit();
            return 1;
        })));
    }

    private static void start(ServerPlayer p) {
        player = p;
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
        p.setHealth(p.getMaxHealth());
        ServerLevel level = p.serverLevel();
        zombie = EntityType.ZOMBIE.create(level);
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
        zombie.getAttribute(Attributes.ARMOR).setBaseValue(0);
        zombie.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        zombie.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1);
        zombie.setHealth(1024);
        level.setDayTime(18000); // night, so the zombie doesn't burn
        place();
        level.addFreshEntity(zombie);
        step = 0;
        wait = 20;
        log("start: zombie elite=%s boss=%s", OverpowerManager.isElite(zombie), OverpowerManager.isBoss(zombie));
    }

    /** Puts the zombie 2 blocks in front of the player, still (knockback drifts an AI-less mob). */
    private static void place() {
        double yaw = Math.toRadians(player.getYRot());
        zombie.moveTo(player.getX() - Math.sin(yaw) * 2, player.getY(), player.getZ() + Math.cos(yaw) * 2, 0, 0);
        zombie.setDeltaMovement(0, 0, 0);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onIncomingFirst(LivingIncomingDamageEvent event) {
        if (zombie == null || (event.getEntity() != zombie && event.getEntity() != player)) return;
        log("  incoming -> %s: %.2f from %s (%s)", event.getEntity().getName().getString(), event.getAmount(),
                event.getSource().getMsgId(), event.getSource().getEntity() == null ? "-" : event.getSource().getEntity().getName().getString());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onIncomingLast(LivingIncomingDamageEvent event) {
        if (zombie == null || (event.getEntity() != zombie && event.getEntity() != player)) return;
        log("  incoming final: %.2f canceled=%s", event.getAmount(), event.isCanceled());
    }

    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Post event) {
        if (zombie == null || (event.getEntity() != zombie && event.getEntity() != player)) return;
        log("  damage applied -> %s: %.2f", event.getEntity().getName().getString(), event.getNewDamage());
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (player == null || zombie == null) return;
        if (wait-- > 0) return;
        OverpowerData zd = OverpowerManager.data(zombie);
        OverpowerData pd = OverpowerManager.data(player);
        long now = player.level().getGameTime();

        // A) up to 40 ticks of clicking; BHB's lock lets one through every ~12 ticks
        if (step < 180) {
            float before = zd.pressure;
            player.attack(zombie);
            step++;
            if (step % 12 == 0 || zombie.hasEffect(OpRegistries.OVERPOWERED)) {
                log("A tick %d: zombie pressure %.1f -> %.1f, overpowered=%s, player pressure %.1f",
                        step, before, zd.pressure, zombie.hasEffect(OpRegistries.OVERPOWERED), pd.pressure);
            }
            if (zombie.hasEffect(OpRegistries.OVERPOWERED)) {
                log("A done: bar broke after %d ticks", step);
                step = 200;
                wait = 20;
            }
            return;
        }
        // B) zombie hits the player until a Glare window opens, then counter
        if (step < 260) {
            place();
            player.setHealth(player.getMaxHealth());
            player.invulnerableTime = 0;
            zombie.doHurtTarget(player);
            step++;
            log("B hit %d: player pressure %.1f, glare window %s", step - 200, pd.pressure,
                    pd.glareTargetId >= 0 ? (pd.glareUntilTick - now) + " ticks" : "closed");
            if (pd.glareTargetId >= 0) {
                zombieHealthBefore = zombie.getHealth();
                player.attack(zombie);
                log("B counter: thrust pending=%s, zombie staggered=%s, player glaring=%s",
                        pd.thrustTargetId >= 0, zombie.hasEffect(OpRegistries.STAGGERED), player.hasEffect(OpRegistries.GLARING));
                step = 300;
                wait = 0;
            } else {
                wait = 3;
            }
            return;
        }
        if (step >= 300 && step < 320) {
            log("B tick %d: thrustTarget=%d at %d (now %d), player pressure %.1f, zombie pressure %.1f",
                    step - 300, pd.thrustTargetId, pd.thrustAtTick, now, pd.pressure, zd.pressure);
            if (pd.thrustTargetId >= 0) place();
            step++;
            if (step < 316) return;
            step = 300 + 99;
        }
        if (step == 399) {
            log("B thrust: zombie health %.1f -> %.1f, zombie pressure %.1f, player pressure %.1f",
                    zombieHealthBefore, zombie.getHealth(), zd.pressure, pd.pressure);
            step = 400;
            wait = 30;
            return;
        }
        // C) off-hand attack
        if (step == 400) {
            place();
            float hp = zombie.getHealth();
            zombie.invulnerableTime = 0;
            log("C pre: canDualWield=%s, canReach=%s, dist=%.2f, offhand=%s", DualWield.canDualWield(player),
                    player.canInteractWithEntity(zombie, 1.0), player.distanceTo(zombie), player.getOffhandItem());
            DualWield.handle(player, zombie.getId(), 0);
            log("C off-hand: zombie health %.1f -> %.1f (off-hand lock %d ticks)", hp, zombie.getHealth(),
                    DualWield.lockTicks(player.getOffhandItem()));
            step = 500;
            wait = 20;
            return;
        }
        // D) guard: block one frontal hit, cooldown, hits from behind land, attacking lowers it
        if (step == 500) {
            place();
            pd.pressure = 0f;
            GuardHandler.toggle(player);
            log("D raise: guarding=%s", GuardHandler.isGuarding(player));
            step = 501;
            wait = 6;
            return;
        }
        if (step == 501) {
            player.setHealth(player.getMaxHealth());
            player.invulnerableTime = 0;
            float hp = player.getHealth();
            zombie.doHurtTarget(player);
            log("D front hit: player health %.1f -> %.1f, guarding=%s, player pressure %.1f (blocked = half)",
                    hp, player.getHealth(), GuardHandler.isGuarding(player), pd.pressure);
            GuardHandler.toggle(player);
            log("D raise during cooldown: guarding=%s (expect false)", GuardHandler.isGuarding(player));
            step = 502;
            wait = 32;
            return;
        }
        if (step == 502) {
            GuardHandler.toggle(player);
            log("D raise after cooldown: guarding=%s", GuardHandler.isGuarding(player));
            double yaw = Math.toRadians(player.getYRot());
            zombie.moveTo(player.getX() + Math.sin(yaw) * 2, player.getY(), player.getZ() - Math.cos(yaw) * 2, 0, 0);
            player.setHealth(player.getMaxHealth());
            player.invulnerableTime = 0;
            float hp = player.getHealth();
            zombie.doHurtTarget(player);
            log("D hit from behind: player health %.1f -> %.1f, guarding=%s (expect still up)",
                    hp, player.getHealth(), GuardHandler.isGuarding(player));
            step = 503;
            wait = 4;
            return;
        }
        if (step == 503) {
            place();
            zombie.invulnerableTime = 0;
            player.attack(zombie);
            log("D attack while guarding: guarding=%s (expect false)", GuardHandler.isGuarding(player));
            place();
            player.setHealth(player.getMaxHealth());
            step = 999;
            log("server phase done");
        }
    }

    /** True once the scripted server phases finish; the client phase (OpTestRunner) then drives the zombie. */
    public static boolean serverPhaseDone() {
        return step == 999 && zombie != null;
    }

    /** {@code /optest hit}: the test zombie hits the player once from the front. */
    private static void frontHit() {
        if (player == null || zombie == null) return;
        place();
        player.setHealth(player.getMaxHealth());
        player.invulnerableTime = 0;
        boolean before = GuardHandler.isGuarding(player);
        float hp = player.getHealth();
        zombie.doHurtTarget(player);
        log("E front hit: guarding %s -> %s, player health %.1f -> %.1f", before, GuardHandler.isGuarding(player),
                hp, player.getHealth());
    }

    /** Server side of every BHB hit in the client phase: which variant the damage used. */
    @SubscribeEvent
    public void onBhbHit(com.bromax.bromaxbattle.api.BhbHitEvent event) {
        if (step != 999 || event.getAttacker() != player) return;
        log("E server variant: %s", event.getAttack() == null ? "-" : event.getAttack().animation);
    }

    private static void log(String fmt, Object... args) {
        BromaxBattle.LOGGER.info("[OpTest] " + String.format(Locale.ROOT, fmt, args));
    }
}
