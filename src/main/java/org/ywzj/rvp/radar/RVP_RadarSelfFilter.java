package org.ywzj.rvp.radar;

import net.minecraft.world.entity.Entity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

/** 雷达自身身份门：同一世界中的本地实体和广播克隆必须按实体 ID 判定，而非对象引用。 */
public final class RVP_RadarSelfFilter {

    private RVP_RadarSelfFilter() {}

    /** 调用方已限定同一世界；克隆 UUID 不保证同步，实体 ID 才是探测包和告警包使用的身份。 */
    public static boolean isSelf(Entity owner, Entity target) {
        return owner != null && target != null && owner.getId() == target.getId();
    }

    /** 清理扫描合并结果，同时保留不同 ID 的同型号、友军及敌军实体。 */
    public static void removeSelfTargets(Entity owner, List<Entity> targets) {
        // 调用统一 ID 校验，覆盖本体重新补入的自身广播克隆。
        targets.removeIf(target -> isSelf(owner, target));
    }

    /** 服务端沿用本体主雷达 DETECT 写入规则，仅拒绝自身目标。 */
    public static void detectNonSelf(WeaponUnit weapon, Entity target) {
        // 调用本体载具归属与主雷达入口，不改变多雷达探测包原有的路由规则。
        if (target == null || isSelf(weapon.getVehicle(), target)) return;
        RadarUnit radar = weapon.getMainRadarUnit();
        if (radar != null) radar.detect(target);
    }

    /** 清除已污染的航迹和硬锁，避免相控阵接触保活或近远对象接管把自身目标长期保留下来。 */
    public static void purgeRadar(RadarUnit radar) {
        // 调用本体探测表入口，兼顾正确 ID 键与历史异常值引用。
        Entity owner = radar.getVehicle();
        radar.getDetectedEntities().entrySet().removeIf(entry -> entry.getKey() == owner.getId()
                || isSelf(owner, entry.getValue().entity));
        // 调用本体解锁入口，客户端沿用本体通知服务端的既有行为。
        if (isSelf(owner, radar.getLockedEntity())) radar.setLockedEntity(null);
    }

    /** 双端已有 tick 调用：清理所有雷达、武器站和 RWR 内残留的自身状态，不触碰其它目标。 */
    public static void purgeVehicle(AbstractVehicle vehicle) {
        // 调用本体部件列表，关机雷达同样清理，防止重新开机后复活自身硬锁。
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof RadarUnit radar) purgeRadar(radar);
            if (part instanceof WeaponUnit weapon && isSelf(vehicle, weapon.getLockedEntity())) {
                // 调用本项目待定锁清理和本体火控解锁，保持雷达与武器站身份一致。
                RVP_RadarRoleHelper.clearPendingRadarLock(weapon);
                weapon.setLockedEntity(null);
            }
        }
        if (vehicle.warningReceiver != null) vehicle.warningReceiver.targets.remove(vehicle.getId());
    }
}
