package org.ywzj.rvp.client;

import net.minecraft.client.Minecraft;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 客户端 RWR 探针：读取与本体 {@code VehicleRadarOverlay} 完全相同的路径
 * （{@code LocalVehiclePlayer.getWeaponUnit().getVehicle().warningReceiver.targets}），
 * 用于排查"主动ECM 伪造锁定已注入但 RWR 不显示"的问题。
 */
public final class RVP_ClientRwrProbe {

    private RVP_ClientRwrProbe() {}

    public static String probe() {
        try {
            LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
            if (lvp == null) {
                return "[RwrProbe] LocalVehiclePlayer 为空";
            }
            WeaponUnit wu = lvp.getWeaponUnit();
            if (wu == null) {
                return "[RwrProbe] weaponUnit 为空 (本体 RWR 覆盖层不会渲染)";
            }
            AbstractVehicle vehicle = wu.getVehicle();
            if (vehicle == null) {
                return "[RwrProbe] weaponUnit.getVehicle() 为空";
            }
            WarningReceiver receiver = vehicle.warningReceiver;
            if (receiver == null || receiver.targets == null) {
                return "[RwrProbe] vehicle=" + vehicle.getId() + " warningReceiver 为空 (本体 RWR 不渲染)";
            }
            int radars = wu.getRadarUnits().size();
            boolean anyLock = receiver.targets.values().stream()
                    .anyMatch(w -> w.warnType() == WarnType.RADAR_LOCK);
            return "[RwrProbe] vehicle=" + vehicle.getId()
                    + " radarUnits=" + radars
                    + " targetsSize=" + receiver.targets.size()
                    + " hasRADAR_LOCK=" + anyLock;
        } catch (Throwable t) {
            return "[RwrProbe] 异常: " + t;
        }
    }
}