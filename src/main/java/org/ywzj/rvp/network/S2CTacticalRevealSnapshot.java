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
    /** 吊舱标记的实体（含 IFF 类型） */
    public List<MarkedEntry> markedEntries = List.of();

    /** 标记实体条目：entityId + IFF 类型 (0=友方蓝, 1=敌方红, 2=中立白) */
    public record MarkedEntry(int entityId, int iffType) {
        /** iff 常量 */
        public static final int IFF_FRIENDLY = 0;
        public static final int IFF_HOSTILE = 1;
        public static final int IFF_NEUTRAL = 2;
    }

    public static void encode(S2CTacticalRevealSnapshot msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.fireRevealIds.size());
        for (Integer entityId : msg.fireRevealIds) {
            buf.writeInt(entityId == null ? Integer.MIN_VALUE : entityId);
        }
        buf.writeVarInt(msg.markedEntries.size());
        for (MarkedEntry entry : msg.markedEntries) {
            buf.writeInt(entry.entityId());
            buf.writeByte(entry.iffType());
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
        List<MarkedEntry> entries = new ArrayList<>(markedSize);
        for (int i = 0; i < markedSize; i++) {
            entries.add(new MarkedEntry(buf.readInt(), buf.readByte()));
        }
        msg.fireRevealIds = fireIds;
        msg.markedEntries = entries;
        return msg;
    }

    public static void handle(S2CTacticalRevealSnapshot msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientTacticalRevealState.applySnapshot(msg)));
    }
}
