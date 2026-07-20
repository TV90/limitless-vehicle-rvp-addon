package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientTacticalRevealState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CTacticalRevealSnapshot {

    public ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    public List<Integer> fireRevealIds = List.of();
    public List<Integer> markedRevealIds = List.of();

    public static void encode(S2CTacticalRevealSnapshot msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.fireRevealIds.size());
        for (Integer entityId : msg.fireRevealIds) {
            buf.writeInt(entityId == null ? Integer.MIN_VALUE : entityId);
        }
        buf.writeVarInt(msg.markedRevealIds.size());
        for (Integer entityId : msg.markedRevealIds) {
            buf.writeInt(entityId == null ? Integer.MIN_VALUE : entityId);
        }
    }

    public static S2CTacticalRevealSnapshot decode(FriendlyByteBuf buf) {
        S2CTacticalRevealSnapshot msg = new S2CTacticalRevealSnapshot();
        msg.dimension = buf.readResourceLocation();
        int fireSize = buf.readVarInt();
        List<Integer> fireIds = new ArrayList<>(fireSize);
        for (int i = 0; i < fireSize; i++) {
            fireIds.add(buf.readInt());
        }
        int markedSize = buf.readVarInt();
        List<Integer> markedIds = new ArrayList<>(markedSize);
        for (int i = 0; i < markedSize; i++) {
            markedIds.add(buf.readInt());
        }
        msg.fireRevealIds = fireIds;
        msg.markedRevealIds = markedIds;
        return msg;
    }

    public static void handle(S2CTacticalRevealSnapshot msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientTacticalRevealState.applySnapshot(msg)));
    }
}
