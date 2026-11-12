package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.weapon.core.RVP_FocusLockSnapState;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.ElectroOptical;

import java.util.List;

/**
 * 焦点锁定吸附载具：EO 传感器下，焦点落在载具 AABB（外扩 radius）内即转成 EO 实体锁，
 * 交回本体 tickFireControl 的实体锁定分支持续跟踪。
 *
 * <p>只处理真实命中点；远景方向代理没有确定距离，不参与吸附。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_FocusLockSnap {

    private RVP_FocusLockSnap() {
    }

    /**
     * 每刻检查当前焦点，命中载具则清焦点并写入实体锁。
     *
     * @param origin 当前观瞄渲染原点，用于排除方向代理锁
     * @return 是否完成吸附
     */
    public static boolean trySnap(WeaponUnit station, @Nullable Vec3 origin) {
        Vec3 focus = station.getFocusLockPos();
        if (focus == null) {
            return false;
        }
        if (!(station.getData() instanceof WeaponUnitDataExt ext)) {
            return false;
        }
        float radius = ext.ywzj_rvp$getFocusLockSnapRadius();
        if (radius <= 0.0f) {
            return false;
        }
        if (RVP_WeaponSensorHelper.effectiveSensorType(station) != WeaponUnitData.FireControlSensorType.EO) {
            return false;
        }
        if (origin != null && RVP_ClientFocusLock.directionOnlyFocus(station, origin) != null) {
            return false;
        }
        AbstractVehicle target = findTarget(station, focus, radius);
        if (target == null) {
            return false;
        }
        // 先清焦点，下一刻本体火控才会走实体锁定分支而不是继续对准死点
        station.setFocusLockPos(null);
        station.setLockedEntity(target);
        // 登记吸附锁定：本体传感器查询在此期间按 EO 返回，实体锁定分支才会正常保留
        RVP_FocusLockSnapState.mark(station, target);
        // 复用既有解除分支：清 LOCKS 并把连续吊舱交回实际姿态，镜头不停在旧焦点
        RVP_ClientActionsAccess.onFocusLockChanged(station);
        return true;
    }

    /** 逐刻校验吸附锁定：目标失效、换车换人、或被本体实体验证清掉时解除登记。 */
    public static void tickUpkeep() {
        List<WeaponUnit> stations = RVP_FocusLockSnapState.stations();
        if (stations.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (WeaponUnit station : stations) {
            if (!stillHolding(station, minecraft)) {
                release(station);
            }
        }
    }

    /** R 键入口在建立新锁前调用：吸附锁仍然有效时先解除并吞掉本次按键。 */
    public static boolean releaseIfSnapped(WeaponUnit station) {
        if (!RVP_FocusLockSnapState.isSnapped(station)) {
            return false;
        }
        release(station);
        return true;
    }

    private static boolean stillHolding(WeaponUnit station, Minecraft minecraft) {
        if (station.getVehicle().level() != minecraft.level
                || station.getOwner() != minecraft.player) {
            return false;
        }
        Entity target = station.getVehicle().level().getEntity(RVP_FocusLockSnapState.targetId(station));
        if (target == null || target.isRemoved() || !target.isAlive()) {
            return false;
        }
        // 本体实体验证或其它锁定入口已经改写锁定槽：吸附不再成立，不抢回来
        return station.getLockedEntity() == target;
    }

    private static void release(WeaponUnit station) {
        int targetId = RVP_FocusLockSnapState.targetId(station);
        RVP_FocusLockSnapState.clear(station);
        Entity locked = station.getLockedEntity();
        if (locked != null && locked.getId() == targetId) {
            station.setLockedEntity(null);
        }
    }

    /** 在焦点附近找最近的、外扩 radius 后仍包含焦点的载具；遮挡判定沿用本体光电口径。 */
    @Nullable
    private static AbstractVehicle findTarget(WeaponUnit station, Vec3 focus, float radius) {
        Level level = station.getVehicle().level();
        AABB search = new AABB(focus, focus).inflate(radius);
        AbstractVehicle best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : level.getEntitiesOfClass(AbstractVehicle.class, search)) {
            if (vehicle == station.getVehicle() || vehicle.isRemoved() || !vehicle.isAlive()) {
                continue;
            }
            if (!vehicle.getBoundingBox().inflate(radius).contains(focus)) {
                continue;
            }
            double distance = vehicle.getBoundingBox().getCenter().distanceToSqr(focus);
            if (distance >= bestDistance) {
                continue;
            }
            if (ElectroOptical.checkTarget(station, vehicle) != vehicle) {
                continue;
            }
            best = vehicle;
            bestDistance = distance;
        }
        return best;
    }
}
