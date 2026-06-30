package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;

import java.util.function.Supplier;

public class C2SSetGPSTarget {

    public enum Action {
        SET_SINGLE,
        ADD_POINT,
        CLEAR_ALL,
        SET_MODE
    }

    public Action action;
    public RVP_ClientGPSState.Mode mode;
    public ResourceLocation dimension;
    public double x;
    public double y;
    public double z;

    public static C2SSetGPSTarget set(ResourceLocation dimension, Vec3 pos) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.SET_SINGLE;
        msg.dimension = dimension;
        msg.x = pos.x;
        msg.y = pos.y;
        msg.z = pos.z;
        msg.mode = RVP_ClientGPSState.Mode.SINGLE;
        return msg;
    }

    public static C2SSetGPSTarget add(ResourceLocation dimension, Vec3 pos) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.ADD_POINT;
        msg.dimension = dimension;
        msg.x = pos.x;
        msg.y = pos.y;
        msg.z = pos.z;
        msg.mode = RVP_ClientGPSState.Mode.MULTI;
        return msg;
    }

    public static C2SSetGPSTarget clear() {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.CLEAR_ALL;
        msg.dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        msg.mode = RVP_ClientGPSState.Mode.SINGLE;
        return msg;
    }

    public static C2SSetGPSTarget setMode(RVP_ClientGPSState.Mode mode) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.SET_MODE;
        msg.mode = mode == null ? RVP_ClientGPSState.Mode.SINGLE : mode;
        msg.dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        return msg;
    }

    public static void encode(C2SSetGPSTarget msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeEnum(msg.mode);
        buf.writeResourceLocation(msg.dimension);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
    }

    public static C2SSetGPSTarget decode(FriendlyByteBuf buf) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = buf.readEnum(Action.class);
        msg.mode = buf.readEnum(RVP_ClientGPSState.Mode.class);
        msg.dimension = buf.readResourceLocation();
        msg.x = buf.readDouble();
        msg.y = buf.readDouble();
        msg.z = buf.readDouble();
        return msg;
    }

    public static void handle(C2SSetGPSTarget msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            GPSTargetManager.Snapshot snapshot;
            if (msg.action == Action.CLEAR_ALL) {
                snapshot = GPSTargetManager.clear(player);
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return;
            }
            if (msg.action == Action.SET_MODE) {
                snapshot = GPSTargetManager.setMode(player, msg.mode);
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return;
            }
            double maxDist = 4096.0;
            Vec3 target = new Vec3(msg.x, msg.y, msg.z);
            if (player.position().distanceToSqr(target) > maxDist * maxDist) {
                snapshot = GPSTargetManager.snapshot(player);
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return;
            }
            snapshot = msg.action == Action.ADD_POINT
                    ? GPSTargetManager.add(player, msg.dimension, target)
                    : GPSTargetManager.set(player, msg.dimension, target);
            RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        });
    }
}
