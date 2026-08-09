package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.function.Supplier;

/**
 * 客户端雷达开关状态同步给服务端。
 * <p>
 * 本体 {@link RadarUnit#toggle(Boolean)} 是纯客户端本地方法（只改客户端 {@code on} 字段，
 * 不发网络包），服务端 {@code isOn()} 恒为 true，导致关闭雷达后服务端仍认为雷达在扫描：
 * 外置雷达共享、RADAR_SEARCH 告警等链路不会随开关停用。本报文在客户端 toggle 后补发，
 * 服务端对对应雷达执行同样的 toggle，使两端开关状态一致。
 * </p>
 */
public class C2SRadarPowerToggle {

    private final int vehicleId;
    private final String radarId;
    private final boolean on;

    public C2SRadarPowerToggle(int vehicleId, String radarId, boolean on) {
        this.vehicleId = vehicleId;
        this.radarId = radarId;
        this.on = on;
    }

    public static void encode(C2SRadarPowerToggle msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
        buf.writeUtf(msg.radarId);
        buf.writeBoolean(msg.on);
    }

    public static C2SRadarPowerToggle decode(FriendlyByteBuf buf) {
        return new C2SRadarPowerToggle(buf.readInt(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(C2SRadarPowerToggle msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !(player.level().getEntity(msg.vehicleId) instanceof AbstractVehicle vehicle)) {
                return;
            }
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (partUnit instanceof RadarUnit radarUnit && radarUnit.getId().equals(msg.radarId)) {
                    if (radarUnit.isOn() != msg.on) {
                        radarUnit.toggle(msg.on);
                    }
                    return;
                }
            }
        });
    }
}
