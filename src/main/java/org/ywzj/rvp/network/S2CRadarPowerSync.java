package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * [RVP] 雷达开关状态服务端→客户端同步（2026-09-28 新增，协议 17）。
 *
 * <p>本体 {@code RadarUnit.on} 是不同步的普通字段：服务端快修恢复雷达后
 * {@code restoreRadar} 的自动开机只发生在服务端实体上，客户端镜像实体的雷达
 * 仍为关闭（雷达页/HMD 读客户端状态），表现为"修好了但雷达没开"。本包把
 * 服务端的开关结果推给客户端镜像执行，两端状态一致。</p>
 */
public class S2CRadarPowerSync {

    public int entityId;
    public String radarId;
    public boolean on;

    public static S2CRadarPowerSync create(int entityId, String radarId, boolean on) {
        return new S2CRadarPowerSync(entityId, radarId, on);
    }

    public static void encode(S2CRadarPowerSync msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeUtf(msg.radarId, 128);
        buf.writeBoolean(msg.on);
    }

    public static S2CRadarPowerSync decode(FriendlyByteBuf buf) {
        return new S2CRadarPowerSync(buf.readInt(), buf.readUtf(128), buf.readBoolean());
    }

    public static void handle(S2CRadarPowerSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientRadarPowerState.apply(msg)));
    }

    public S2CRadarPowerSync(int entityId, String radarId, boolean on) {
        this.entityId = entityId;
        this.radarId = radarId;
        this.on = on;
    }
}
