package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;

import java.util.function.Supplier;

public class C2STVMissileControlInput {

    public int missileEntityId;
    public float yaw;
    public float pitch;
    public int seq;

    public static C2STVMissileControlInput of(int missileEntityId, float yaw, float pitch, int seq) {
        C2STVMissileControlInput msg = new C2STVMissileControlInput();
        msg.missileEntityId = missileEntityId;
        msg.yaw = yaw;
        msg.pitch = pitch;
        msg.seq = seq;
        return msg;
    }

    public static void encode(C2STVMissileControlInput msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);
        buf.writeInt(msg.seq);
    }

    public static C2STVMissileControlInput decode(FriendlyByteBuf buf) {
        C2STVMissileControlInput msg = new C2STVMissileControlInput();
        msg.missileEntityId = buf.readInt();
        msg.yaw = buf.readFloat();
        msg.pitch = buf.readFloat();
        msg.seq = buf.readInt();
        return msg;
    }

    public static void handle(C2STVMissileControlInput msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.level() == null) {
                return;
            }
            Entity e = player.level().getEntity(msg.missileEntityId);
            if (!(e instanceof TVMissileEntity missile)) {
                return;
            }
            if (missile.getOwner() != player) {
                return;
            }
            missile.ywzj_rvp$setTVMissileInput(msg.yaw, msg.pitch, msg.seq);
        });
    }
}
