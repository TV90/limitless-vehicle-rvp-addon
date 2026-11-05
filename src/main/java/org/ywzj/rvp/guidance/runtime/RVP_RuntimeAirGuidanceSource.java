package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

/** AIR 的雷达中段与弹载红外搜索：只有有效传感器观测才能更新目标实时位置。 */
public final class RVP_RuntimeAirGuidanceSource implements RVP_RuntimeGuidanceSource {
    @Override public RVP_EnumGuidanceType type() { return RVP_EnumGuidanceType.AIR; }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (!(context.projectile() instanceof RVP_MissileEntity missile)) return RVP_GuidanceIntent.failed(type());
        Entity target = missile.getTargetEntity();
        // 调用统一反制状态；烟雾、关闭期和干扰保持期不能被指定目标或雷达中继重新绕过。
        boolean blocked = missile.isSeekerShutOff() || missile.isJamGracePeriodActive() || missile.hasSmokeBreakLock();
        if (missile.isAutonomousSeekerOn() && !blocked) {
            if (missile.hasAutonomousSeekerCatch() && target != null && target.isAlive()
                    && RVP_AirSeekerPolicy.isAirInfraredCandidate(target)) {
                // 调用红外跟踪与干扰检查，已捕获目标不再依赖机载雷达或 RCS。
                Entity tracked = RVP_RuntimeSeekerSupport.validateEntity(missile, target, type(), context.active());
                if (tracked != null) return RVP_GuidanceIntent.entity(tracked, false, 1.0, type());
                missile.setTargetEntity(null);
                missile.clearAutonomousSeekerCatch();
                blocked = missile.isSeekerShutOff() || missile.isJamGracePeriodActive() || missile.hasSmokeBreakLock();
            }
            if (!blocked) {
                Entity designated = missile.rvp$getActiveSeekerDesignatedTargetEntity();
                if (RVP_AirSeekerPolicy.isAirInfraredCandidate(designated)) {
                    // 调用捕获门限；指定身份仅是搜索提示，超出红外距离/离轴角不能获得实时位置。
                    Entity acquired = RVP_RuntimeSeekerSupport.acquireEntity(missile, designated, type(), context.active());
                    if (acquired != null) return acquired(missile, acquired);
                    blocked = missile.isSeekerShutOff() || missile.isJamGracePeriodActive() || missile.hasSmokeBreakLock();
                }
                int interval = context.active().scanIntervalTick() == null ? 2 : context.active().scanIntervalTick();
                if (!blocked && missile.getFlightTickCount() % interval == 0) {
                    // 调用专用红外候选扫描；不使用 RCS 距离，也不纳入箔条。
                    Entity scanned = RVP_RuntimeSeekerSupport.scanActiveInfraredTarget(missile, context.active());
                    if (scanned != null) {
                        Entity acquired = RVP_RuntimeSeekerSupport.acquireEntity(missile, scanned, type(), context.active());
                        if (acquired != null) return acquired(missile, acquired);
                    }
                }
            }
        }
        return relayOrMemory(context, missile);
    }

    private RVP_GuidanceIntent acquired(RVP_MissileEntity missile, Entity target) {
        missile.setTargetEntity(target);
        missile.markAutonomousSeekerCatch();
        return RVP_GuidanceIntent.entity(target, false, 1.0, type());
    }

    private RVP_GuidanceIntent relayOrMemory(RVP_GuidanceRuntimeContext context, RVP_MissileEntity missile) {
        // 雷达确实支持原指定目标时允许中段更新，包括雷达硬锁箔条；不把它标成红外捕获。
        if (!missile.hasSmokeBreakLock() && !missile.isSeekerShutOff() && !missile.isJamGracePeriodActive()
                && missile.rvp$hasAirRadarRelaySupport()) {
            Entity designated = missile.rvp$getActiveSeekerDesignatedTargetEntity();
            if (RVP_AirSeekerPolicy.allowsTarget(designated)) {
                missile.setTargetEntity(designated);
                return RVP_GuidanceIntent.entity(designated, false, 1.0, type());
            }
        }
        missile.setTargetEntity(null);
        missile.clearAutonomousSeekerCatch();
        Vec3 memory = missile.hasSmokeBreakLock() && missile.getSmokeInertialPoint() != null
                ? missile.getSmokeInertialPoint()
                : missile.getTargetPos() != null ? missile.getTargetPos() : missile.getLastGuidancePos();
        return memory != null && (context.active().enableInertialGuidance() || missile.hasSmokeBreakLock())
                ? RVP_GuidanceIntent.point(memory, false, 1.0, type()) : RVP_GuidanceIntent.failed(type());
    }
}
