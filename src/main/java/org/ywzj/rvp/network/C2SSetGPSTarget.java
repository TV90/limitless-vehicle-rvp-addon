package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;

import java.util.function.Supplier;

public class C2SSetGPSTarget {

    public boolean active;
    public ResourceLocation dimension;
    public double x;
    public double y;
    public double z;

    public static C2SSetGPSTarget set(ResourceLocation dimension, Vec3 pos) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.active = true;
        msg.dimension = dimension;
        msg.x = pos.x;
        msg.y = pos.y;
        msg.z = pos.z;
        return msg;
    }

    public static C2SSetGPSTarget clear() {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.active = false;
        msg.dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        return msg;
    }

    public static void encode(C2SSetGPSTarget msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.active);
        buf.writeResourceLocation(msg.dimension);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
    }

    public static C2SSetGPSTarget decode(FriendlyByteBuf buf) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.active = buf.readBoolean();
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
            if (!msg.active) {
                GPSTargetManager.clear(player);
                return;
            }
            double maxDist = 4096.0;
            Vec3 target = new Vec3(msg.x, msg.y, msg.z);
            if (player.position().distanceToSqr(target) > maxDist * maxDist) {
                return;
            }
            GPSTargetManager.set(player, msg.dimension, target);
        });
    }
}
