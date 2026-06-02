package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import java.util.function.Supplier;

public class S2CSetTVMissile {
    public int missileEntityId;

    public static S2CSetTVMissile set(int missileEntityId) {
        S2CSetTVMissile msg = new S2CSetTVMissile();
        msg.missileEntityId = missileEntityId;
        return msg;
    }

    public static void encode(S2CSetTVMissile msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
    }

    public static S2CSetTVMissile decode(FriendlyByteBuf buf) {
        S2CSetTVMissile msg = new S2CSetTVMissile();
        msg.missileEntityId = buf.readInt();
        return msg;
    }

    public static void handle(S2CSetTVMissile msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RvpClientTVMissileState.setActiveMissileId(msg.missileEntityId)));
    }
}
