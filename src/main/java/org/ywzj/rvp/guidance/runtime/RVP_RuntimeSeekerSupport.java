package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.radar.RVP_AspectRcs;
import org.ywzj.rvp.radar.RVP_RadarScanHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.rvp.guidance.RVP_GuidanceTargetUtil;

final class RVP_RuntimeSeekerSupport {

    /** 重锁/搜索扫描半径封顶（格）：避免按 guidanceTargetDistanceRange（可达上千格）做超大
     * getEntities 箱子扫描导致 TPS 掉刻；干扰物/目标在末段导弹附近，256 格足够覆盖重锁场景。 */
    private static final double ACQUIRE_SCAN_RADIUS = 256.0;

    private RVP_RuntimeSeekerSupport() {}

    /** 重锁/搜索扫描半径：取配置距离范围与封顶的较小值。 */
    private static double acquireScanRadius(RVP_BaseBullet projectile, RVP_GuidanceActiveConfig config) {
        var searchRange = RVP_GuidanceRuntimeGeometry.resolveAcquireDistanceRange(projectile, config);
        double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(searchRange);
        // 显式末段搜索区间是独立包络；只遍历已加载实体，不放大区块查询或持续跟踪距离。
        if (searchRange != config.targetDistanceRange()) return range;
        return Math.min(range, ACQUIRE_SCAN_RADIUS);
    }

    @Nullable
    static Entity validateEntity(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_EnumGuidanceType type,
            RVP_GuidanceActiveConfig config
    ) {
        return validateEntity(projectile, target, type, config, false);
    }

    @Nullable
    static Entity acquireEntity(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_EnumGuidanceType type,
            RVP_GuidanceActiveConfig config
    ) {
        return validateEntity(projectile, target, type, config, true);
    }

    @Nullable
    private static Entity validateEntity(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_EnumGuidanceType type,
            RVP_GuidanceActiveConfig config,
            boolean acquire
    ) {
        // 捕获与持续跟踪共用归属门，指定目标不能绕过自由搜索的友方弹药过滤。
        if (!org.ywzj.rvp.guidance.RVP_SeekerTargetFilter.allows(projectile, target)) return null;
        // AIR 主动红外不允许把雷达指定的箔条套成自主捕获或继续跟踪。
        if (type == RVP_EnumGuidanceType.AIR && !RVP_AirSeekerPolicy.isInfraredCandidate(target)) return null;
        // 反舰导引头（2026-10-05 用户需求）：仅截获阶段（acquire）过滤——目标 RCS 综合值
        // 不足 SCAN_SEA_RCS_THRESHOLD（500，与 scan_sea 海面搜索同门限）时拒绝截获：
        // 雷达/RF 硬锁指定的目标不满足 RCS 也不会被导引头自动套上（含自由扫描候选）。
        // 跟踪阶段（acquire=false）不过滤——已截获目标的结算不在此处。
        // SARH 无自主导引头，其"导引头"即照射追击链（validateEntity 亦为其唯一过滤点）：
        // 照射目标不达标 → intent failed → 惯性直飞。
        boolean antiShipGate = type != RVP_EnumGuidanceType.AIR && config.antiShipSeeker()
                && (acquire || type == RVP_EnumGuidanceType.SARH);
        if (antiShipGate
                && !(target instanceof AbstractVehicle antiShipCandidate
                && RVP_AspectRcs.combinedFactor(antiShipCandidate, projectile.position())
                >= RVP_RadarScanHelper.SCAN_SEA_RCS_THRESHOLD)) {
            return null;
        }
        boolean withinLimits = acquire
                ? RVP_GuidanceRuntimeGeometry.passesAcquireLimits(projectile, target, config)
                : RVP_GuidanceRuntimeGeometry.passesTrackEnvelope(projectile, target, config);
        if (!withinLimits) {
            return null;
        }
        // Phase 3 被动电子战：ARH/SARH 导引头截获时按距离档概率偏转到假目标
        if (acquire && (type == RVP_EnumGuidanceType.ARH || type == RVP_EnumGuidanceType.SARH)) {
            Entity diverted = org.ywzj.rvp.ecm.RVP_EcmPassiveManager.tryDivertSeeker(projectile, target, type);
            if (diverted != null) {
                projectile.setTargetEntity(diverted);
                projectile.markJamGracePeriod();
                return diverted;
            }
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(projectile, target, type, config);
        // 每次脱锁判定的综合状态诊断（开关：/rvpdebug flags jam，节流 20 tick）
        if (RVP_DebugFlags.JAM.isEnabled() && projectile.tickCount % 20 == 0) {
            String near = RVP_CountermeasureState.debugNearestDecoyString(projectile, target,
                    RVP_CountermeasureState.decoyTypeFor(type), config);
            System.out.println("[RVP-DBG][JamQuery] seeker=" + projectile.getId()
                    + " type=" + type + " target=" + (target == null ? "null" : target.getClass().getSimpleName())
                    + " intercepted=" + result.intercepted() + " decoyed=" + result.decoyed()
                    + " denied=" + result.isDenied()
                    + " | " + near);
        }
        if (result.intercepted()) {
            // 拦截才会直接移除；留下明确原因，避免与箔条/热焰弹导致的普通脱锁混淆。
            org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.noteEvent(projectile,
                    org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.Event.GUIDANCE,
                    () -> "reason=COUNTERMEASURE_INTERCEPTED action=discard guidanceType=" + type);
            projectile.discard();
            return null;
        }
        if (result.decoyed()) {
            // 按制导类型在导引头视场锥内找对应干扰物（IR/AIR→热焰弹，SARH/ARH→箔条），转锁最近诱饵
            Entity decoy = RVP_CountermeasureState.findDecoyInSeekerCone(
                    projectile, target, RVP_CountermeasureState.decoyTypeFor(type), config).orElse(null);
            // 脱锁后是否找到可转锁干扰物诊断（开关：/rvpdebug flags jam，节流 20 tick）
            if (RVP_DebugFlags.JAM.isEnabled() && projectile.tickCount % 20 == 0) {
                System.out.println("[RVP-DBG][JamRetarget] seeker=" + projectile.getId()
                        + " decoyed=YES oldTarget=" + (projectile.getTargetEntity() == null ? "null" : projectile.getTargetEntity().getClass().getSimpleName())
                        + " retarget=" + (decoy == null ? "LOST_COAST" : "id=" + decoy.getId()));
            }
            if (decoy == null) {
                // 脱锁判定成立但未找到可重锁诱饵（记忆尾迹/诱饵全部脱离检测区）：进入干扰保持期，
                // 期间维持近炸抑制与载具碰撞免疫，coast 滑行不得恢复活弹直击玩家（也不再回锁机体）
                projectile.markJamGracePeriod();
                return null;
            }
            projectile.setTargetEntity(decoy);
            projectile.markJamGracePeriod();
            return decoy;
        }
        if (result.isDenied()) {
            // 未捕获时只是本次搜索被挡，不能提前写成“已跟踪后永久烟雾脱锁”。
            if (acquire && projectile.isTerminalSeekerHandoffPending()) return null;
            // 光学被挡/入烟等锁定阻断：捕获具体烟雾实体，记录脱锁瞬间算定的固定惯导落点，
            // 并标记烟雾脱锁（永久阻止复锁，与诱饵转锁分隔），进入干扰保持期
            RVP_SmokeEntity smoke = RVP_CountermeasureState.findSmokeContaining(target);
            Vec3 smokePoint = smoke != null
                    ? RVP_CountermeasureState.computeSmokeInertialPoint(smoke, target) : null;
            projectile.markSmokeBreakLock(smokePoint);
            projectile.markJamGracePeriod();
            return null;
        }
        return target;
    }

    @Nullable
    static Entity scanRadarTarget(RVP_BaseBullet projectile, RVP_GuidanceActiveConfig config) {
        double range = acquireScanRadius(projectile, config);
        Iterable<Entity> all = allServerEntities(projectile);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 look = projectile.getLookAngle();
        Vec3 pos = projectile.position();
        // O(实体) 遍历已加载实体，替代 Radar.scanTargets 的 ±range 立方体 getEntities（O(箱子截面)）
        for (Entity entity : all) {
            if (!org.ywzj.rvp.guidance.RVP_SeekerTargetFilter.allows(projectile, entity)
                    || !entity.isAlive() || entity == projectile.getShooterVehicle()
                    || (!(entity instanceof RVP_BaseBullet) && entity.getBoundingBox().getSize() < 1)
                    || !isRadarScannable(entity)) {
                continue;
            }
            // 弹药归属已由上方统一过滤器判定，不能再按 owner 重复排除独立炮火支援。
            // 目的：分角度 RCS + 弹舱开启增幅（2026-09-16）——载具目标的导引头截获距离
            // 按"扫描半径 × 综合隐身因子"缩放（正面隐身机要贴近才可被截获）。
            // 仅雷达导引头分支生效；AIR 主动红外不受 RCS 影响。非载具（HBM 导弹）因子 1。
            // 反舰导引头（2026-10-05）：主动段开机自由扫描也只能寻的 RCS 综合值 ≥500 的大型
            // 目标（舰船）——非载具与小型目标直接跳过，不进入评分候选。
            if (config.antiShipSeeker()
                    && (!(entity instanceof AbstractVehicle antiShipCandidate)
                    || RVP_AspectRcs.combinedFactor(antiShipCandidate, pos)
                    < RVP_RadarScanHelper.SCAN_SEA_RCS_THRESHOLD)) {
                continue;
            }
            double effectiveRange = range;
            if (entity instanceof AbstractVehicle targetVehicle) {
                effectiveRange = range * RVP_AspectRcs.detectionFactor(targetVehicle, pos);
            } else if (entity instanceof RVP_BaseBullet bullet) {
                // 调用实体弹体的分角度信号计算；虚拟对象也按迎头/侧向/尾向缩放截获距离。
                effectiveRange *= bullet.getRadarSignatureTowards(pos);
            }
            if (entity.distanceToSqr(projectile) > effectiveRange * effectiveRange) {
                continue;
            }
            if (!RVP_GuidanceRuntimeGeometry.passesAcquireLimits(projectile, entity, config)) {
                continue;
            }
            // [RVP] §46：评分点与瞄准点同源（最大 OBB 中心；诱饵等非载具自动回退 AABB 中心）
            Vec3 toTarget = org.ywzj.rvp.util.RVP_AimPointResolver
                    .resolveLargestObbCenter(entity).subtract(pos);
            double angle = RVP_GuidanceTargetUtil.angleBetween(look, toTarget);
            double score = angle * 4.0 + entity.distanceTo(projectile) / Math.max(effectiveRange, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        // ARH 开启导引头后可锁箔条：扫描候选含 CHAFF 实体，按 chaffResistance 施加优先级罚分
        // （越大越难被选为锁定目标，但非完全不可锁）
        for (Entity entity : all) {
            if (!(entity instanceof RVP_Decoy decoy)
                    || decoy.rvp$decoyType() != RVP_EnumCountermeasureType.CHAFF
                    || !entity.isAlive()) {
                continue;
            }
            if (entity.distanceToSqr(projectile) > range * range) {
                continue;
            }
            if (!RVP_GuidanceRuntimeGeometry.passesAcquireLimits(projectile, entity, config)) {
                continue;
            }
            Vec3 toTarget = org.ywzj.rvp.util.RVP_AimPointResolver
                    .resolveLargestObbCenter(entity).subtract(pos);
            double angle = RVP_GuidanceTargetUtil.angleBetween(look, toTarget);
            double score = angle * 4.0 + entity.distanceTo(projectile) / Math.max(range, 1.0)
                    + config.chaffResistance() * 30.0;
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    @Nullable
    static Entity scanInfraredTarget(RVP_BaseBullet projectile, RVP_GuidanceActiveConfig config) {
        return scanInfraredTarget(projectile, config, false);
    }

    /** AIR 自主红外按自身配置搜索，并保留对具有红外信号的来袭弹药的拦截能力。 */
    @Nullable
    static Entity scanActiveInfraredTarget(RVP_BaseBullet projectile, RVP_GuidanceActiveConfig config) {
        return scanInfraredTarget(projectile, config, true);
    }

    private static Entity scanInfraredTarget(RVP_BaseBullet projectile, RVP_GuidanceActiveConfig config, boolean active) {
        // 已加载实体遍历不申请区块；AIR 的配置范围不再被雷达重捕获用的256格上限截断。
        double range = active ? RVP_GuidanceRuntimeGeometry.resolveScanRadius(config.targetDistanceRange())
                : acquireScanRadius(projectile, config);
        Iterable<Entity> all = allServerEntities(projectile);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 look = projectile.getLookAngle();
        Vec3 pos = projectile.position();
        // O(实体) 遍历已加载实体，替代 getEntities(±range 箱子)（O(箱子截面)）
        for (Entity entity : all) {
            if (entity == projectile.getShooterVehicle() || !entity.isAlive()
                    || entity instanceof AbstractVehicle vehicle && vehicle.isDestroyed()
                    || !org.ywzj.rvp.guidance.RVP_SeekerTargetFilter.allows(projectile, entity)) {
                continue;
            }
            // 归属沿用上方统一过滤器；AIR 仍额外要求来袭弹有红外信号，不能重复按 owner 拒绝炮火。
            if (entity == projectile || active && entity instanceof RVP_BaseBullet ammo
                    && ammo.getSignatureSize() <= 0f) continue;
            boolean isTarget = entity instanceof AbstractVehicle
                    || active && entity instanceof RVP_BaseBullet
                    // IR 开启导引头阶段把热焰弹也当作锁定目标（无抗性）
                    || entity instanceof RVP_Decoy decoy
                    && decoy.rvp$decoyType() == RVP_EnumCountermeasureType.FLARE;
            if (!isTarget) {
                continue;
            }
            if (entity.distanceToSqr(projectile) > range * range) {
                continue;
            }
            if (!RVP_GuidanceRuntimeGeometry.passesAcquireLimits(projectile, entity, config)) {
                continue;
            }
            // 自主搜索先排除不可见目标再评分；否则锥心被遮挡的车会永远挡住旁边可见目标。
            if (!RVP_CountermeasureState.isOpticalTargetVisible(projectile, entity)) continue;
            // [RVP] §46：IR 评分点与瞄准点同源（最大 OBB 中心；热焰弹等非载具自动回退）
            Vec3 toTarget = org.ywzj.rvp.util.RVP_AimPointResolver
                    .resolveLargestObbCenter(entity).subtract(pos);
            double angle = RVP_GuidanceTargetUtil.angleBetween(look, toTarget);
            double score = angle / Math.max(config.maxLockHalfAngle(), 1.0)
                    + entity.distanceTo(projectile) / Math.max(range, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    /** 服务端已加载实体（O(实体)）：替代 Radar.scanTargets / getEntities(box) 的 O(箱子截面) 扫描。
     * 导引头扫描只在服务端跑（客户端走 super.tickGuidance），非服务端返回空。 */
    private static Iterable<Entity> allServerEntities(Entity owner) {
        if (owner.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            // 调用统一目标源，主动导引头重捕获可搜索到真实和虚拟来袭弹体。
            return org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileTargets.allEntities(sl);
        }
        return java.util.List.of();
    }

    private static boolean isRadarScannable(Entity entity) {
        return RVP_GuidanceTargetUtil.isRadarScannableTarget(entity);
    }
}
