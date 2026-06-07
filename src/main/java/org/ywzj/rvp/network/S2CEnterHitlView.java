package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;

import java.util.function.Supplier;

public class S2CEnterHitlView {

    public int missileEntityId;
    public RVP_EnumHitlControlMode controlMode = RVP_EnumHitlControlMode.VIEW;

    public static S2CEnterHitlView of(int missileEntityId, RVP_EnumHitlControlMode controlMode) {
        S2CEnterHitlView msg = new S2CEnterHitlView();
        msg.missileEntityId = missileEntityId;
        msg.controlMode = controlMode != null ? controlMode : RVP_EnumHitlControlMode.VIEW;
        return msg;
    }

    public static void encode(S2CEnterHitlView msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
        buf.writeEnum(msg.controlMode);
    }

    public static S2CEnterHitlView decode(FriendlyByteBuf buf) {
        S2CEnterHitlView msg = new S2CEnterHitlView();
        msg.missileEntityId = buf.readInt();
        msg.controlMode = buf.readEnum(RVP_EnumHitlControlMode.class);
        return msg;
    }

    public static void handle(S2CEnterHitlView msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientHitlState.enter(msg.missileEntityId, msg.controlMode)));
    }
}
