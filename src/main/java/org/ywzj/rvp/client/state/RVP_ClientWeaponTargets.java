package org.ywzj.rvp.client.state;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.util.RVP_AimPointResolver;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.ElectroOptical;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端武器的统一候选与身份解析；显示代理仅作为服务器实体的本地表示。 */
public final class RVP_ClientWeaponTargets {
    private RVP_ClientWeaponTargets() {}

    /** 合并世界实体与远距代理；同 ID 优先真实实体，不能把同一辆车当成两个目标。 */
    public static List<Entity> entities(ClientLevel level) {
        Map<Integer, Entity> targets = new LinkedHashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isAlive()) {
                targets.put(entity.getId(), entity);
            }
        }
        // 调用服务端授权的代理候选入口，与渲染器的绘制数量限制解耦。
        for (AbstractVehicle proxy : RVP_ClientRemoteVehicleVisualState.targetEntities(level)) {
            targets.putIfAbsent(proxy.getId(), proxy);
        }
        return List.copyOf(targets.values());
    }

    /** 搜索、驻留计时、保锁和 HUD 都按同一个服务端 ID 完成近远交接。 */
    @Nullable
    public static Entity resolve(ClientLevel level, int entityId) {
        Entity real = level.getEntity(entityId);
        if (real != null && real.isAlive()) {
            return real;
        }
        // 调用远距状态表；已撤权或过期的代理不能通过旧锁继续存活。
        return RVP_ClientRemoteVehicleVisualState.resolveTarget(level, entityId);
    }

    /** RVP 红外按武器自己的距离、角度、高度和遮挡门搜索，消除本体固定 512 格候选截断。 */
    @Nullable
    public static Entity findInfraredTarget(WeaponUnit unit, float searchHalfAngle) {
        // 调用包装解包入口，兼容多弹种与子武器站。
        var weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        if (weapon == null || !(unit.getVehicle().level() instanceof ClientLevel level)) {
            // 调用本体搜索，保留非 RVP 武器原有行为。
            return Infrared.findTarget(unit, searchHalfAngle);
        }
        Vec3 origin = unit.worldPivotPosition();
        Vec3 direction = unit.worldVec();
        Entity best = null;
        double bestAngle = Double.POSITIVE_INFINITY;
        // 调用合并候选入口；逐个校验后再排序，避免最近的无效目标挡住有效目标。
        for (Entity entity : entities(level)) {
            if (weapon.getData().getGuidanceData().getGuidanceType()
                    == org.ywzj.rvp.guidance.RVP_EnumGuidanceType.AIR
                    && !org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.isAirInfraredCandidate(entity)) continue;
            // 调用弹体信号尺寸，保留原 InfraredSignatureMixin 对小型可探测弹药的放行规则。
            double infraredSize = entity instanceof RVP_BaseBullet bullet && bullet.getSignatureSize() > 0f
                    ? bullet.getSignatureSize() : entity.getBoundingBox().getSize();
            if (entity.getId() == unit.getVehicle().getId() || entity.getVehicle() != null
                    || entity instanceof PartEntity<?> || entity.isSpectator()
                    || entity instanceof AbstractVehicle vehicle && vehicle.isDestroyed()
                    || infraredSize < 1) {
                continue;
            }
            // 调用统一瞄准点与本体夹角算法，候选排序和锁定验证使用同一车体位置。
            Vec3 offset = RVP_AimPointResolver.resolveLargestObbCenter(entity).subtract(origin);
            double angle = Math.toDegrees(VectorUtil.angleBetween(offset, direction));
            if (angle <= searchHalfAngle && angle < bestAngle
                    && RVP_IrLockHelper.isTargetWithinAcquireLimits(unit, entity, weapon.getData())) {
                best = entity;
                bestAngle = angle;
            }
        }
        return best;
    }

    /** 在已有火控 Tick 尾部刷新引用；同 ID 交接不重置红外驻留或重新授权。 */
    public static void refreshStationLock(WeaponUnit station) {
        // 调用根站归一入口；雷达锁仍由自身探测表维护，不能绕过雷达发现规则。
        WeaponUnit root = station.getRootParentWeaponUnit();
        if (root == null || root.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF
                || !(root.getVehicle().level() instanceof ClientLevel level)
                || !(root.getLockedEntity() instanceof AbstractVehicle locked)) {
            return;
        }
        Entity current = resolve(level, locked.getId());
        if (current != locked && (current != null || locked.remote)) {
            // 调用本体锁定同步，只在表示对象改变或代理撤权时写入。
            root.setLockedEntity(current);
        }
    }

    /** 给既有 R 键入口补充远距光电命中；再次按键解锁仍交给本体。 */
    public static boolean tryManualRemoteLock(WeaponUnit station) {
        if (!(station.getVehicle().level() instanceof ClientLevel level) || station.getLockedEntity() != null
                || station.getMainRadarUnit() != null && station.getMainRadarUnit().getLockedEntity() != null) {
            return false;
        }
        Vec3 lookAt;
        var view = LocalVehiclePlayer.instance.viewType;
        if (view == LocalVehiclePlayer.ViewType.SCOPE) {
            lookAt = LocalVehiclePlayer.instance.scopeAimPos();
        } else if (view == LocalVehiclePlayer.ViewType.THIRD_PERSON || view == LocalVehiclePlayer.ViewType.OPERATOR) {
            lookAt = LocalVehiclePlayer.instance.freeAimPos();
        } else {
            return false;
        }
        Vec3 start = station.worldOpticalSightPosition(1f);
        var opticalRay = RVP_ClientOpticalSight.sampleRay(station, net.minecraft.client.Minecraft.getInstance().getFrameTime());
        if (opticalRay != null) {
            // 新版吊舱沿本帧画面搜索；普通站继续使用原 lookAt 和挂点。
            start = opticalRay.origin();
            lookAt = start.add(opticalRay.direction().scale(RVP_ClientHmdState.EO_HMD_MAX_RANGE));
        }
        // 光电沿用现有 512 格最大范围；画面视距调为 2 区块不能截断实体选择射线。
        Vec3 end = start.add(lookAt.subtract(start).normalize().scale(RVP_ClientHmdState.EO_HMD_MAX_RANGE));
        EntityHitResult closest = VectorUtil.hitEntity(station.getVehicle(), start, end);
        double nearest = closest == null ? Double.POSITIVE_INFINITY : start.distanceToSqr(closest.getLocation());
        AbstractVehicle target = null;
        for (AbstractVehicle proxy : RVP_ClientRemoteVehicleVisualState.targetEntities(level)) {
            if (proxy.getId() == station.getVehicle().getId() || level.getEntity(proxy.getId()) != null) {
                continue;
            }
            // 调用真实受击 OBB 射线，车体空洞不能因为代理的外包围盒而被误选。
            var boxes = org.ywzj.rvp.util.RVP_NamedVehicleObbs.collect(proxy);
            var hit = org.ywzj.rvp.util.RVP_NamedVehicleObbs.raycast(boxes, start, end);
            if (hit != null && hit.distanceSquared() < nearest) {
                nearest = hit.distanceSquared();
                target = proxy;
            }
        }
        if (target == null) {
            return false;
        }
        // 调用本体光电视线检查，并限制在最近不透光地形之前，不绕过烟幕等视觉遮挡。
        var block = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, station.getVehicle()));
        if (block.getType() != HitResult.Type.MISS && start.distanceToSqr(block.getLocation()) < nearest
                || ElectroOptical.checkTarget(station, target) == null) {
            return false;
        }
        // 调用武器解析和开机入口；开机会清旧锁，因此必须先开机再写入新目标。
        if (RVP_WeaponResolveHelper.currentPrimaryRvp(station) != null && !station.isSeekerOn()) {
            station.toggleSeeker(true);
        }
        station.setFocusLockPos(null);
        station.setLockedEntity(target);
        return true;
    }
}
