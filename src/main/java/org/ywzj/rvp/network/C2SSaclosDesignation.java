package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;

import java.util.function.Supplier;

/** Client → server: SACLOS laser designator on/off + live aim point (MCH PacketLaserGuidanceTargeting). */
public class C2SSaclosDesignation {

    public boolean targeting;
    public double x;
    public double y;
    public double z;

    public static C2SSaclosDesignation of(boolean targeting, Vec3 point) {
        C2SSaclosDesignation msg = new C2SSaclosDesignation();
        msg.targeting = targeting;
        if (point != null) {
            msg.x = point.x;
            msg.y = point.y;
            msg.z = point.z;
        }
        return msg;
    }

    public static void encode(C2SSaclosDesignation msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.targeting);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
    }

    public static C2SSaclosDesignation decode(FriendlyByteBuf buf) {
        C2SSaclosDesignation msg = new C2SSaclosDesignation();
        msg.targeting = buf.readBoolean();
        msg.x = buf.readDouble();
        msg.y = buf.readDouble();
        msg.z = buf.readDouble();
        return msg;
    }

    public static void handle(C2SSaclosDesignation msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Vec3 point = msg.targeting ? new Vec3(msg.x, msg.y, msg.z) : null;
            RVP_SaclosOperatorSession.setDesignation(player.getUUID(), msg.targeting, point);
        });
    }
}
