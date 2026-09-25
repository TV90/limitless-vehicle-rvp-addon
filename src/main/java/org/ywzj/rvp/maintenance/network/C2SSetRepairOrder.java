package org.ywzj.rvp.maintenance.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.maintenance.server.RVP_RepairOrderTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 维修顺序上行（C2S，协议 16 新增）：辅助设备面板编辑"爆反维修顺序 / 辅助设备维修顺序"
 * 后即时同步，服务端经 {@link RVP_RepairOrderTable} 按载具记入侧表（车组共享）。
 * 面板只负责设置顺序、不触发维修——快修触发仍走玩家手持快修工具的既有链路
 * （G 键 / {@link C2SUseMaintenance}），恢复目标在 {@code recoverModules} 消费时队列头优先。
 */
public class C2SSetRepairOrder {

    /** 目标载具实体 id。 */
    public int vehicleId;
    /** 爆反（ERA）维修顺序：骨名列表，队首先修。 */
    public List<String> eraBones;
    /** 辅助设备（非 ERA 骨模块，如 APS/ECM/干扰机）维修顺序：骨名列表，队首先修。 */
    public List<String> deviceBones;

    public C2SSetRepairOrder() {
    }

    public C2SSetRepairOrder(int vehicleId, List<String> eraBones, List<String> deviceBones) {
        this.vehicleId = vehicleId;
        this.eraBones = eraBones;
        this.deviceBones = deviceBones;
    }

    public static void encode(C2SSetRepairOrder msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleId);
        buf.writeVarInt(msg.eraBones.size());
        for (String bone : msg.eraBones) {
            buf.writeUtf(bone, 256);
        }
        buf.writeVarInt(msg.deviceBones.size());
        for (String bone : msg.deviceBones) {
            buf.writeUtf(bone, 256);
        }
    }

    public static C2SSetRepairOrder decode(FriendlyByteBuf buf) {
        int vehicleId = buf.readVarInt();
        // 队列长度上限 64（与 RVP_RepairOrderTable 一致）：decode 侧先钳制再读，防恶意超长包
        int eraCount = Math.min(buf.readVarInt(), 64);
        List<String> eraBones = new ArrayList<>(eraCount);
        for (int i = 0; i < eraCount; i++) {
            eraBones.add(buf.readUtf(256));
        }
        int deviceCount = Math.min(buf.readVarInt(), 64);
        List<String> deviceBones = new ArrayList<>(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            deviceBones.add(buf.readUtf(256));
        }
        return new C2SSetRepairOrder(vehicleId, eraBones, deviceBones);
    }

    public static void handle(C2SSetRepairOrder msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level().getEntity(msg.vehicleId);
            if (!(entity instanceof AbstractVehicle vehicle)) {
                return;
            }
            // 必须乘坐在目标载具上：维修顺序是车组共享状态，防车外玩家越权改写
            if (player.getVehicle() != vehicle) {
                return;
            }
            // 仅对配置了快修（bone_modules 维修模块）的载具生效：未配置车无维修顺序可言
            if (RVP_VehicleHitboxFactorManager.INSTANCE.resolveMaintenanceModule(vehicle) == null) {
                return;
            }
            // 逐条合法性在快修触发消费时按"骨块确实失效 + 类型可修"服务端权威校验，
            // 这里只做保序去重收纳（无效骨名永远匹配不到失效集，无越权面）。
            RVP_RepairOrderTable.setOrder(vehicle.getUUID(), msg.eraBones, msg.deviceBones);
        });
    }
}
