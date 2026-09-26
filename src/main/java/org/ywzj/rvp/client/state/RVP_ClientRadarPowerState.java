package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.network.S2CRadarPowerSync;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

/**
 * [RVP] 雷达开关状态服务端→客户端执行器（2026-09-28 新增）。
 *
 * <p>本体 {@code RadarUnit.on} 不参与同步：服务端强制关闭（雷达被击毁）或快修恢复
 * 自动开机时，客户端镜像实体的雷达开关不会跟随。本执行器收到
 * {@link S2CRadarPowerSync} 后在客户端镜像实体上执行同样的 {@code toggle}，
 * 使雷达页/HMD/扫描行为与权威状态一致。</p>
 */
public final class RVP_ClientRadarPowerState {

    private RVP_ClientRadarPowerState() {
    }

    /** 收包入口（客户端主线程）。 */
    public static void apply(S2CRadarPowerSync msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(msg.entityId);
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        vehicle.getPartUnit(msg.radarId).ifPresent(partUnit -> {
            if (partUnit instanceof RadarUnit radarUnit && radarUnit.isOn() != msg.on) {
                radarUnit.toggle(msg.on);
            }
        });
    }
}
