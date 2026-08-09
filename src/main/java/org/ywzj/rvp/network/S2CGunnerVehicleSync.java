package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientGunnerVehicleState;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：Gunner 驾驶的载具阵营与航向同步。
 *
 * <p>替代被删 {@code AbstractVehicleGunnerDataMixin}（原注入本体 {@code writeData/readData} 同步
 * 远程实体的 faction / yRot / xRot）。非 mixin 方案：RVP 自建低频广播，客户端侧表按 entityId 存储。</p>
 */
public class S2CGunnerVehicleSync {
    public ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    public List<Entry> entries = List.of();

    public S2CGunnerVehicleSync() {}

    public S2CGunnerVehicleSync(ResourceLocation dimension, List<Entry> entries) {
        this.dimension = dimension;
        this.entries = entries;
    }

    public record Entry(int entityId, float yRot, float xRot, RVP_EnumGunnerFaction faction) {}

    public static void encode(S2CGunnerVehicleSync msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeInt(entry.entityId());
            buf.writeFloat(entry.yRot());
            buf.writeFloat(entry.xRot());
            buf.writeEnum(entry.faction());
        }
    }

    public static S2CGunnerVehicleSync decode(FriendlyByteBuf buf) {
        S2CGunnerVehicleSync msg = new S2CGunnerVehicleSync();
        msg.dimension = buf.readResourceLocation();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(
                    buf.readInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readEnum(RVP_EnumGunnerFaction.class)
            ));
        }
        msg.entries = entries;
        return msg;
    }

    public static void handle(S2CGunnerVehicleSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientGunnerVehicleState.applySnapshot(msg)));
    }
}
