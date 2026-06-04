package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import java.util.function.Supplier;

public class C2STVMissileExit {
    public int missileEntityId;

    public static C2STVMissileExit of(int missileEntityId) {
        C2STVMissileExit msg = new C2STVMissileExit();
        msg.missileEntityId = missileEntityId;
        return msg;
    }

    public static void encode(C2STVMissileExit msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
    }

    public static C2STVMissileExit decode(FriendlyByteBuf buf) {
        C2STVMissileExit msg = new C2STVMissileExit();
        msg.missileEntityId = buf.readInt();
        return msg;
    }

    public static void handle(C2STVMissileExit msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.level() == null) {
                return;
            }
            Entity e = player.level().getEntity(msg.missileEntityId);
            if (!(e instanceof RVP_MissileEntity missile)) {
                return;
            }
            if (missile.getOwner() != player) {
                return;
            }
            missile.rvp$exitTVMissile();
        });
    }
}
