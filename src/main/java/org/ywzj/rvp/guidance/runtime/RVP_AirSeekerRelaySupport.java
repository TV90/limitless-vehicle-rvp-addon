package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarScanHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** AIR 发射授权与飞行中共用的雷达支持查询，保留实时 RCS 和配置扫描范围。 */
public final class RVP_AirSeekerRelaySupport {
    private RVP_AirSeekerRelaySupport() {}

    /** 调用整车雷达和已连接的外置雷达；旧站级锁引用本身不构成支持。 */
    public static boolean isAvailable(WeaponUnit station, Entity target) {
        if (station == null || target == null || !target.isAlive()) return false;
        WeaponUnit root = station.getRootParentWeaponUnit();
        if (root == null || root.getVehicle() == null) return false;
        AbstractVehicle vehicle = root.getVehicle();
        if (vehicleSupports(vehicle, target)) return true;
        AbstractVehicle relay = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(vehicle).orElse(null);
        return relay != null && vehicleSupports(relay, target);
    }

    private static boolean vehicleSupports(AbstractVehicle vehicle, Entity target) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof RadarUnit radar && radarSupports(radar, target)) return true;
        }
        return false;
    }

    private static boolean radarSupports(RadarUnit radar, Entity target) {
        if (!radar.isOn() || RVP_RadarScanHelper.isVehicleOnly(radar) && !(target instanceof AbstractVehicle)) return false;
        // 调用现有探测包络，距离随目标当前 RCS（含弹舱变化）缩放，不用旧锁绕过隐身。
        boolean envelope = RVP_RadarScanHelper.isWithinIlluminationEnvelope(radar, target);
        if (target instanceof RVP_Decoy decoy && decoy.rvp$decoyType() == RVP_EnumCountermeasureType.CHAFF) {
            // 箔条只接受真实雷达硬锁；脱锁后即使仍在包络内也不保留更新。
            return RVP_AirSeekerPolicy.chaffRelayAvailable(envelope, radar.getLockedEntity() == target);
        }
        return RVP_AirSeekerPolicy.radarSupports(true, envelope, false);
    }
}
