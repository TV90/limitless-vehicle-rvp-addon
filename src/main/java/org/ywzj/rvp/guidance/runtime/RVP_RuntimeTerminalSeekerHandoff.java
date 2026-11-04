package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.*;
import org.ywzj.rvp.radar.RVP_AspectRcs;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 末段搜索与主段操纵并行；只有真实捕获才一次性交出主段制导权。 */
public final class RVP_RuntimeTerminalSeekerHandoff {
    private RVP_RuntimeTerminalSeekerHandoff() {}

    /** 调用已有导引头搜索、反制及截获门；失败意图不得冒充惯导点捕获。 */
    public static RVP_GuidanceIntent acquire(RVP_GuidanceRuntimeContext context) {
        RVP_BaseBullet projectile = context.projectile();
        RVP_EnumGuidanceType type = context.active().guidanceType();
        if (projectile.dircmJammed || projectile.isSeekerShutOff()
                || projectile.isJamGracePeriodActive() || projectile.hasSmokeBreakLock()) {
            return RVP_GuidanceIntent.failed(type);
        }
        if (type == RVP_EnumGuidanceType.ARM) {
            // 调用反辐射原入口；等待接管时该入口只允许本刻真实辐射源成功。
            return new RVP_RuntimeArmGuidanceSource().evaluate(context);
        }
        if (type != RVP_EnumGuidanceType.IR && type != RVP_EnumGuidanceType.ARH) {
            return RVP_GuidanceIntent.failed(type);
        }
        int interval = context.active().scanIntervalTick() == null ? 2 : context.active().scanIntervalTick();
        if (projectile.getFlightTickCount() % interval != 0) return RVP_GuidanceIntent.failed(type);
        // 主段指定身份只能作为搜索候选；必须重新通过末段捕获门，不能直接套用持续跟踪门。
        Entity candidate = projectile.getTargetEntity();
        if (candidate == null && projectile instanceof RVP_MissileEntity missile) {
            candidate = missile.rvp$getActiveSeekerDesignatedTargetEntity();
        }
        Entity target = acquireCandidate(context, candidate);
        if (target == null && !projectile.isRemoved() && !projectile.isSeekerShutOff()
                && !projectile.isJamGracePeriodActive() && !projectile.hasSmokeBreakLock()) {
            // 调用既有已加载实体扫描，不申请远方区块；IR 与 ARH 候选渠道保持分离。
            Entity scanned = type == RVP_EnumGuidanceType.IR
                    ? RVP_RuntimeSeekerSupport.scanInfraredTarget(projectile, context.active())
                    : RVP_RuntimeSeekerSupport.scanRadarTarget(projectile, context.active());
            target = acquireCandidate(context, scanned);
        }
        if (target == null) return RVP_GuidanceIntent.failed(type);
        projectile.setTargetEntity(target);
        if (type == RVP_EnumGuidanceType.IR) {
            projectile.markTerminalIrTargetAcquired();
            projectile.resetIrSeekerGrace();
        } else {
            projectile.setAutonomousSeekerOn(true);
            projectile.markAutonomousSeekerCatch();
        }
        return RVP_GuidanceIntent.entity(target, false, 1.0, type);
    }

    /** 同时约束指定目标和自由扫描，防止旧雷达锁或主段目标绕过末段搜索限制。 */
    private static Entity acquireCandidate(RVP_GuidanceRuntimeContext context, Entity candidate) {
        if (candidate == null || !candidate.isAlive()) return null;
        RVP_BaseBullet projectile = context.projectile();
        RVP_EnumGuidanceType type = context.active().guidanceType();
        if (type == RVP_EnumGuidanceType.IR && !RVP_AirSeekerPolicy.isInfraredCandidate(candidate)) return null;
        if (type == RVP_EnumGuidanceType.ARH) {
            if (projectile.ecmActiveNoReacquire || projectile.ecmActiveJamRemainTick > 0) return null;
            // 调用现有分角度 RCS：搜索距离及开机距离都不能由主段旧实体引用绕过。
            double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                    RVP_GuidanceRuntimeGeometry.resolveAcquireDistanceRange(projectile, context.active()));
            if (context.active().activeRadarActivationRange() > 0) {
                range = Math.min(range, context.active().activeRadarActivationRange());
            }
            if (candidate instanceof AbstractVehicle vehicle) range *= RVP_AspectRcs.detectionFactor(vehicle, projectile.position());
            else if (candidate instanceof RVP_BaseBullet ammo) range *= ammo.getRadarSignatureTowards(projectile.position());
            if (candidate.distanceToSqr(projectile) > range * range) return null;
        }
        // 调用既有截获判定，继续执行烟雾、遮挡、诱饵及反制规则。
        return RVP_RuntimeSeekerSupport.acquireEntity(projectile, candidate, type, context.active());
    }

    /** 捕获提示共用本地化键；末段 AIR 已经在数据出口统一路由为 IR。 */
    public static String captureMessageKey(RVP_EnumGuidanceType type) {
        return switch (type) {
            case ARM -> "message.ywzj_rvp.seeker_captured_arm";
            case ARH -> "message.ywzj_rvp.seeker_captured_radar";
            case IR, AIR -> "message.ywzj_rvp.seeker_captured_ir";
            default -> null;
        };
    }
}
