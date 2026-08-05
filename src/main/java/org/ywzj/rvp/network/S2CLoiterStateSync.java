package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientLoiterState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：同步所有活跃盘旋圆信息供战术地图渲染。
 */
public class S2CLoiterStateSync {

    public List<RVP_ClientLoiterState.LoiterCircle> circles = List.of();

    public static void encode(S2CLoiterStateSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.circles.size());
        for (RVP_ClientLoiterState.LoiterCircle c : msg.circles) {
            buf.writeResourceLocation(c.dimension());
            buf.writeDouble(c.centerX());
            buf.writeDouble(c.centerZ());
            buf.writeDouble(c.radius());
            buf.writeBoolean(c.active());
            buf.writeInt(c.vehicleEntityId());
        }
    }

    public static S2CLoiterStateSync decode(FriendlyByteBuf buf) {
        S2CLoiterStateSync msg = new S2CLoiterStateSync();
        int size = buf.readVarInt();
        List<RVP_ClientLoiterState.LoiterCircle> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ResourceLocation dim = buf.readResourceLocation();
            double cx = buf.readDouble();
            double cz = buf.readDouble();
            double r = buf.readDouble();
            boolean active = buf.readBoolean();
            int vehicleId = buf.readInt();
            list.add(new RVP_ClientLoiterState.LoiterCircle(dim, cx, cz, r, active, vehicleId));
        }
        msg.circles = list;
        return msg;
    }

    public static void handle(S2CLoiterStateSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientLoiterState.applySnapshot(msg.circles)));
    }
}
