package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.guidance.RVP_OpticalLock;

import java.util.function.Supplier;

public record S2COpticalFeedback(int vehicleId, ResourceLocation weapon, int missileId, int sequence, int targetId,
                                Vec3 point, float confidence) {
    public static void launched(ServerPlayer player, int vehicle, ResourceLocation weapon, int missile) {
        send(player, new S2COpticalFeedback(vehicle, weapon, missile, 0, -1, null, 0f));
    }

    public static void locked(ServerPlayer player, int vehicle, ResourceLocation weapon, int sequence, RVP_OpticalLock.Result lock) {
        send(player, new S2COpticalFeedback(vehicle, weapon, -1, sequence,
                lock == null || lock.entity() == null ? -1 : lock.entity().getId(),
                lock == null ? null : lock.point(), lock == null ? 0f : lock.confidence()));
    }

    private static void send(ServerPlayer player, S2COpticalFeedback msg) {
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void encode(S2COpticalFeedback msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeResourceLocation(msg.weapon);
        buf.writeVarInt(msg.missileId);
        buf.writeVarInt(msg.sequence);
        buf.writeVarInt(msg.targetId);
        buf.writeNullable(msg.point, (b, v) -> b.writeDouble(v.x).writeDouble(v.y).writeDouble(v.z));
        buf.writeFloat(msg.confidence);
    }

    public static S2COpticalFeedback decode(FriendlyByteBuf buf) {
        return new S2COpticalFeedback(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readNullable(b -> new Vec3(b.readDouble(), b.readDouble(), b.readDouble())), buf.readFloat());
    }

    public static void handle(S2COpticalFeedback msg, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientOpticalState.receive(msg));
    }
}
