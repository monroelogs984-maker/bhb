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

    /** Client -> server: off-hand attack on an entity (-1 = swing at air). */
    public record OffhandAttack(int targetId) implements CustomPacketPayload {
        public static final Type<OffhandAttack> TYPE = new Type<>(id("offhand_attack"));
        public static final StreamCodec<ByteBuf, OffhandAttack> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OffhandAttack::targetId, OffhandAttack::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> clients tracking the attacker: play the off-hand attack animation. */
    public record OffhandSwing(int entityId) implements CustomPacketPayload {
        public static final Type<OffhandSwing> TYPE = new Type<>(id("offhand_swing"));
        public static final StreamCodec<ByteBuf, OffhandSwing> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OffhandSwing::entityId, OffhandSwing::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BromaxBattle.MOD_ID, path);
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(OpNetwork::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(Hud.TYPE, Hud.CODEC, (p, ctx) -> ClientHooks.hud(p));
        r.playToClient(Glare.TYPE, Glare.CODEC, (p, ctx) -> ClientHooks.glare(p));
        r.playToClient(OffhandSwing.TYPE, OffhandSwing.CODEC, (p, ctx) -> ClientHooks.offhandSwing(p));
        r.playToServer(OffhandAttack.TYPE, OffhandAttack.CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> DualWield.handle((ServerPlayer) ctx.player(), p.targetId())));
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

    public static void offhandSwing(ServerPlayer player) {
        PacketDistributor.sendToPlayersTrackingEntity(player, new OffhandSwing(player.getId()));
    }

    /** Indirection so the dedicated server never loads client classes. */
    private static final class ClientHooks {
        static void hud(Hud p) { com.bromax.bromaxbattle.overpower.client.OverpowerHud.receive(p); }
        static void glare(Glare p) { com.bromax.bromaxbattle.overpower.client.GlareAnimations.play(p.entityId(), p.poseTicks()); }
        static void offhandSwing(OffhandSwing p) { com.bromax.bromaxbattle.overpower.client.DualWieldClient.remoteSwing(p.entityId()); }
    }
}
