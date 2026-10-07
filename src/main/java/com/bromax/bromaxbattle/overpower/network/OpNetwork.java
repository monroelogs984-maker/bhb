package com.bromax.bromaxbattle.overpower.network;

import com.bromax.bromaxbattle.BromaxBattle;
import com.bromax.bromaxbattle.overpower.combat.OverpowerData;
import com.bromax.bromaxbattle.overpower.combat.OverpowerManager;
import com.bromax.bromaxbattle.overpower.dualwield.DualWield;
import com.bromax.bromaxbattle.overpower.registry.OpRegistries;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class OpNetwork {
    private OpNetwork() {}

    /** Server -> client: the player's own pressure, their target's, and any open Glare window. */
    public record Hud(float self, int targetId, float target, int glareTicksLeft, int glareWindow, boolean glaring)
            implements CustomPacketPayload {
        public static final Type<Hud> TYPE = new Type<>(id("hud"));
        public static final StreamCodec<ByteBuf, Hud> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Hud::self, ByteBufCodecs.VAR_INT, Hud::targetId, ByteBufCodecs.FLOAT, Hud::target,
                ByteBufCodecs.VAR_INT, Hud::glareTicksLeft, ByteBufCodecs.VAR_INT, Hud::glareWindow, ByteBufCodecs.BOOL, Hud::glaring,
                Hud::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> clients tracking the player: play the Glare pose, then the thrust after poseTicks. */
    public record Glare(int entityId, int poseTicks) implements CustomPacketPayload {
        public static final Type<Glare> TYPE = new Type<>(id("glare"));
        public static final StreamCodec<ByteBuf, Glare> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Glare::entityId, ByteBufCodecs.VAR_INT, Glare::poseTicks, Glare::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: off-hand attack on an entity (-1 = swing at air) with the variant the client played. */
    public record OffhandAttack(int targetId, int variant) implements CustomPacketPayload {
        public static final Type<OffhandAttack> TYPE = new Type<>(id("offhand_attack"));
        public static final StreamCodec<ByteBuf, OffhandAttack> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OffhandAttack::targetId, ByteBufCodecs.VAR_INT, OffhandAttack::variant, OffhandAttack::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> clients tracking the attacker: play the off-hand attack animation. */
    public record OffhandSwing(int entityId, int variant) implements CustomPacketPayload {
        public static final Type<OffhandSwing> TYPE = new Type<>(id("offhand_swing"));
        public static final StreamCodec<ByteBuf, OffhandSwing> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OffhandSwing::entityId, ByteBufCodecs.VAR_INT, OffhandSwing::variant, OffhandSwing::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: the variant the client chose (and is animating) for its next main-hand attack. */
    public record AttackVariant(int variant) implements CustomPacketPayload {
        public static final Type<AttackVariant> TYPE = new Type<>(id("attack_variant"));
        public static final StreamCodec<ByteBuf, AttackVariant> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AttackVariant::variant, AttackVariant::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: the guard key was pressed. */
    public record GuardToggle() implements CustomPacketPayload {
        public static final Type<GuardToggle> TYPE = new Type<>(id("guard_toggle"));
        public static final StreamCodec<ByteBuf, GuardToggle> CODEC = StreamCodec.unit(new GuardToggle());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> the player and everyone tracking them: guard raised/lowered, and any cooldown before it can rise again. */
    public record GuardState(int entityId, boolean up, int cooldownTicks) implements CustomPacketPayload {
        public static final Type<GuardState> TYPE = new Type<>(id("guard_state"));
        public static final StreamCodec<ByteBuf, GuardState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, GuardState::entityId, ByteBufCodecs.BOOL, GuardState::up,
                ByteBufCodecs.VAR_INT, GuardState::cooldownTicks, GuardState::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BromaxBattle.MOD_ID, path);
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(OpNetwork::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("2");
        r.playToClient(Hud.TYPE, Hud.CODEC, (p, ctx) -> ClientHooks.hud(p));
        r.playToClient(Glare.TYPE, Glare.CODEC, (p, ctx) -> ClientHooks.glare(p));
        r.playToClient(OffhandSwing.TYPE, OffhandSwing.CODEC, (p, ctx) -> ClientHooks.offhandSwing(p));
        r.playToServer(OffhandAttack.TYPE, OffhandAttack.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> DualWield.handle((ServerPlayer) ctx.player(), p.targetId(), p.variant())));
        r.playToServer(AttackVariant.TYPE, AttackVariant.CODEC, (p, ctx) -> ctx.enqueueWork(() ->
                com.bromax.bromaxbattle.combat.CombatHandler.INSTANCE.setChosenVariant(ctx.player().getUUID(), p.variant())));
        r.playToServer(GuardToggle.TYPE, GuardToggle.CODEC, (p, ctx) -> ctx.enqueueWork(() ->
                com.bromax.bromaxbattle.combat.GuardHandler.toggle((ServerPlayer) ctx.player())));
        r.playToClient(GuardState.TYPE, GuardState.CODEC, (p, ctx) -> ClientHooks.guard(p));
    }

    public static void syncHud(ServerPlayer player) {
        OverpowerData d = OverpowerManager.data(player);
        long now = player.level().getGameTime();
        d.lastSyncTick = now;
        Entity t = d.hudTargetId >= 0 ? player.level().getEntity(d.hudTargetId) : null;
        float tp = 0f;
        if (t instanceof LivingEntity living && living.isAlive() && living.hasData(OpRegistries.DATA)) {
            tp = living.getData(OpRegistries.DATA).pressure;
        }
        int left = d.glareTargetId >= 0 ? (int) Math.max(0, d.glareUntilTick - now) : 0;
        PacketDistributor.sendToPlayer(player, new Hud(d.pressure, d.hudTargetId, tp, left, d.glareWindowTicks,
                d.thrustTargetId >= 0));
    }

    public static void playGlare(ServerPlayer player, int poseTicks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new Glare(player.getId(), poseTicks));
    }

    public static void offhandSwing(ServerPlayer player, int variant) {
        PacketDistributor.sendToPlayersTrackingEntity(player, new OffhandSwing(player.getId(), variant));
    }

    public static void guardState(ServerPlayer player, boolean up, int cooldownTicks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new GuardState(player.getId(), up, cooldownTicks));
    }

    /** Indirection so the dedicated server never loads client classes. */
    private static final class ClientHooks {
        static void hud(Hud p) { com.bromax.bromaxbattle.overpower.client.OverpowerHud.receive(p); }
        static void glare(Glare p) { com.bromax.bromaxbattle.overpower.client.GlareAnimations.play(p.entityId(), p.poseTicks()); }
        static void offhandSwing(OffhandSwing p) { com.bromax.bromaxbattle.overpower.client.DualWieldClient.remoteSwing(p.entityId(), p.variant()); }
        static void guard(GuardState p) { com.bromax.bromaxbattle.client.GuardClient.receive(p.entityId(), p.up(), p.cooldownTicks()); }
    }
}
