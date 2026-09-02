package org.ywzj.rvp.maintenance.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.maintenance.server.RVP_MaintenanceRuntimeManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * 客户端按下快速维修键 → 服务端触发一次维修（服务端权威校验：
 * 已配置启用 / 未被摧毁 / 冷却就绪 / 玩家在本车上 / 离地高度限制）。
 */
public class C2SUseMaintenance {

    public int vehicleId;

    public C2SUseMaintenance() {
    }

    public C2SUseMaintenance(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public static void encode(C2SUseMaintenance msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
    }

    public static C2SUseMaintenance decode(FriendlyByteBuf buf) {
        C2SUseMaintenance msg = new C2SUseMaintenance();
        msg.vehicleId = buf.readVarInt();
        return msg;
    }

    public static void handle(C2SUseMaintenance msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level().getEntity(msg.vehicleId);
            if (entity instanceof AbstractVehicle vehicle) {
                // 服务端权威：内部完成全部校验（配置/冷却/乘载/高度），失败静默忽略
                RVP_MaintenanceRuntimeManager.tryStart(player, vehicle);
            }
        });
    }
}
