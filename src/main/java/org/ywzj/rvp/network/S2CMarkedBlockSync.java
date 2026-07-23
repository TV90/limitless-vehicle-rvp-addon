package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientMarkedBlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C 方块标记同步包。同步当前维度内所有有效方块标记的坐标。
 */
public class S2CMarkedBlockSync {

    public ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    public List<MarkedBlockEntry> blocks = List.of();

    public record MarkedBlockEntry(float x, float y, float z) {}

    public static void encode(S2CMarkedBlockSync msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.blocks.size());
        for (MarkedBlockEntry entry : msg.blocks) {
            buf.writeFloat(entry.x);
            buf.writeFloat(entry.y);
            buf.writeFloat(entry.z);
        }
    }

    public static S2CMarkedBlockSync decode(FriendlyByteBuf buf) {
        S2CMarkedBlockSync msg = new S2CMarkedBlockSync();
        msg.dimension = buf.readResourceLocation();
        int size = buf.readVarInt();
        List<MarkedBlockEntry> blocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            blocks.add(new MarkedBlockEntry(buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        msg.blocks = blocks;
        return msg;
    }

    public static void handle(S2CMarkedBlockSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientMarkedBlockState.applySnapshot(msg)));
    }
}
