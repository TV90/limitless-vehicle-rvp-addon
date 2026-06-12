package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CHitlLinkState {

    public int missileEntityId;
    public boolean blocked;
    public boolean severed;

    public static S2CHitlLinkState of(int missileEntityId, boolean blocked, boolean severed) {
        S2CHitlLinkState msg = new S2CHitlLinkState();
        msg.missileEntityId = missileEntityId;
        msg.blocked = blocked;
        msg.severed = severed;
        return msg;
    }

    public static void encode(S2CHitlLinkState msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
        buf.writeBoolean(msg.blocked);
        buf.writeBoolean(msg.severed);
    }

    public static S2CHitlLinkState decode(FriendlyByteBuf buf) {
        S2CHitlLinkState msg = new S2CHitlLinkState();
        msg.missileEntityId = buf.readInt();
        msg.blocked = buf.readBoolean();
        msg.severed = buf.readBoolean();
        return msg;
    }

    public static void handle(S2CHitlLinkState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientHitlState.onHitlLinkState(
                        msg.missileEntityId, msg.blocked, msg.severed)));
    }
}
