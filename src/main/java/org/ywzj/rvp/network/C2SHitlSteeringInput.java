package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;

import java.util.function.Supplier;

public class C2SHitlSteeringInput {

    public int missileEntityId;
    public float yaw;
    public float pitch;
    public int seq;

    public static C2SHitlSteeringInput of(int missileEntityId, float yaw, float pitch, int seq) {
        C2SHitlSteeringInput msg = new C2SHitlSteeringInput();
        msg.missileEntityId = missileEntityId;
        msg.yaw = yaw;
        msg.pitch = pitch;
        msg.seq = seq;
        return msg;
    }

    public static void encode(C2SHitlSteeringInput msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);
        buf.writeInt(msg.seq);
    }

    public static C2SHitlSteeringInput decode(FriendlyByteBuf buf) {
        C2SHitlSteeringInput msg = new C2SHitlSteeringInput();
        msg.missileEntityId = buf.readInt();
        msg.yaw = buf.readFloat();
        msg.pitch = buf.readFloat();
        msg.seq = buf.readInt();
        return msg;
    }

    public static void handle(C2SHitlSteeringInput msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer player = ctx.getSender();
        if (player == null || player.level() == null) {
            ctx.setPacketHandled(true);
            return;
        }
        Entity e = player.level().getEntity(msg.missileEntityId);
        if (!(e instanceof RVP_MissileEntity missile) || missile.getOwner() != player) {
            ctx.setPacketHandled(true);
            return;
        }
        missile.rvp$setHitlSteeringInput(msg.yaw, msg.pitch, msg.seq);
        ctx.setPacketHandled(true);
    }
}
