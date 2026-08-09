package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_JammingRuntime;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_CommandGuidanceAim;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * SACLOS guidance source with optional semi-active correction.
 *
 * <p>When {@code semiCorrectionEnabled} is false, behaves like original SACLOS:
 * the missile flies toward a point on the operator's line of sight at scan radius distance.
 *
 * <p>When {@code semiCorrectionEnabled} is true, copies LBR beam-riding logic as the base
 * (target = foot of perpendicular + forward offset along LOS), then adds a separate
 * underdamped spring-mass-damper oscillator on top.
 *
 * <p>The oscillator operates in **world-space 3D vectors** (not per-tick perp basis),
 * to prevent the perp basis from rotating with LOS and causing circular motion.
 *
 * <p>This produces the desired behavior:
 * <ul>
 *   <li>LOS stable: missile rides the beam like LBR, with small noise-driven wobble</li>
 *   <li>LOS rotates: delta_phys kicks the oscillator → overshoot → oscillation → decay</li>
 * </ul>
 */
public final class RVP_RuntimeSaclosGuidanceSource implements RVP_RuntimeGuidanceSource {

    /** Forward offset along LOS from the perpendicular foot, matching LBR. */
    private static final double FORWARD_OFFSET = 32.0;

    /** Maximum perpendicular offset clamp (blocks). */
    private static final double MAX_OFFSET = 30.0;

    /** Noise refresh interval (ticks). */
    private static final int NOISE_INTERVAL = 10;

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SACLOS;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        RVP_BaseBullet projectile = context.projectile();
        RVP_GuidanceActiveConfig active = context.active();

        Vec3 direction = RVP_CommandGuidanceAim.operatorAimDirection(
                projectile.getShooterWeaponUnit());
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            resetSaclosState(projectile);
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }
        Vec3 dir = direction.normalize();

        // 被干扰期间：切断玩家手动制导——导弹不再跟随操作手视线。
        // 制导目标改为「最后记忆点 + 朝远离干扰机一侧的大幅横向偏移」：
        // 每 tick 制导都会把导弹拉向该偏移点（而非拉回原方向），实现持续大幅横向偏航。
        double jamStrength = RVP_JammingRuntime.tickAndResolve(projectile);
        if (jamStrength > 0.0) {
            Vec3 memory = projectile.getLastGuidancePos();
            if (memory != null) {
                Vec3 jamTarget = applyJamming(projectile, dir, memory, jamStrength);
                projectile.setTargetPos(jamTarget);
                return RVP_GuidanceIntent.point(jamTarget, false, 1.0, RVP_EnumGuidanceType.SACLOS);
            }
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SACLOS);
        }

        // Original SACLOS behavior when semi-correction is disabled
        if (!active.semiCorrectionEnabled()) {
            double range = RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                    active.targetDistanceRange());
            Vec3 point = projectile.position().add(dir.scale(range));
            point = applyJamming(projectile, dir, point, jamStrength);
            projectile.setTargetPos(point);
            return RVP_GuidanceIntent.point(point, true, 1.0, RVP_EnumGuidanceType.SACLOS);
        }

        // === Semi-correction enabled: LBR base + spring-damper oscillator ===

        // -- LBR base logic (copied from RVP_RuntimeLbrGuidanceSource) --
        WeaponUnit shooterUnit = projectile.getShooterWeaponUnit();
        Vec3 beamOrigin = shooterUnit != null ? shooterUnit.worldPivotPosition() : Vec3.ZERO;
        Vec3 missilePos = projectile.position();

        // Foot of perpendicular from missile to LOS ray
        Vec3 foot = closestPointOnRay(beamOrigin, dir, missilePos);

        // LBR base target: foot + forward offset along LOS
        Vec3 baseTarget = foot.add(dir.scale(FORWARD_OFFSET));

        // -- Spring-damper oscillator (world-space 3D vectors) --
        // Physical perpendicular displacement of missile from LOS (world space)
        Vec3 toMissile = missilePos.subtract(foot);
        Vec3 perpComponent = toMissile.subtract(dir.scale(toMissile.dot(dir)));

        // Delta of physical offset since last tick (perturbation from LOS movement)
        Vec3 deltaPhys = perpComponent.subtract(projectile.saclosLastPhysVec);
        projectile.saclosLastPhysVec = perpComponent;

        double stiffness = active.semiCorrectionStiffness();
        double damping = active.semiCorrectionDamping();
        double wobble = active.semiCorrectionWobble();

        // Noise for steady-state wobble (random direction in world space)
        Vec3 noise = Vec3.ZERO;
        if (wobble > 0.0 && projectile.tickCount % NOISE_INTERVAL == 0) {
            noise = randomPerpVector(dir, projectile, wobble);
        }

        // Free oscillator in world space:
        //   spring pulls offset toward zero
        //   damping resists velocity
        //   delta_phys kicks (from LOS rotation)
        //   noise adds steady-state wobble
        Vec3 springForce = projectile.saclosOffsetVec.scale(-stiffness);
        Vec3 dampingForce = projectile.saclosVelVec.scale(-damping);
        Vec3 accel = springForce.add(dampingForce).add(deltaPhys).add(noise);

        projectile.saclosVelVec = projectile.saclosVelVec.add(accel);
        projectile.saclosOffsetVec = projectile.saclosOffsetVec.add(projectile.saclosVelVec);

        // Clamp offset magnitude
        double offsetMag = projectile.saclosOffsetVec.length();
        if (offsetMag > MAX_OFFSET) {
            double scale = MAX_OFFSET / offsetMag;
            projectile.saclosOffsetVec = projectile.saclosOffsetVec.scale(scale);
        }

        // Final target: LBR base + oscillation offset (world space, no perp basis rotation)
        Vec3 finalTarget = baseTarget.add(projectile.saclosOffsetVec);
        finalTarget = applyJamming(projectile, dir, finalTarget, jamStrength);

        projectile.setTargetPos(finalTarget);
        return RVP_GuidanceIntent.point(finalTarget, true, 1.0, RVP_EnumGuidanceType.SACLOS);
    }

    private static void resetSaclosState(RVP_BaseBullet projectile) {
        projectile.saclosOffsetVec = Vec3.ZERO;
        projectile.saclosVelVec = Vec3.ZERO;
        projectile.saclosLastPhysVec = Vec3.ZERO;
    }

    /** 速度旋转单 tick 转角上限（度）×强度，防止配置异常时导弹瞬间翻飞。 */
    private static final double JAM_HEADING_MAX_DEG = 80.0;

    /**
     * 叠加干扰错误分量（干扰强度由 {@link #evaluate} 传入，被干扰时 evaluate 已切断手动制导，
     * 此处不再调用 {@code tickAndResolve}）。
     *
     * <p>偏移方向**由干扰机相对弹体飞行线的左右侧决定**（{@code jammingSideSign}）：
     * 干扰机在导弹左侧 → 把导弹推向导弹右侧（远离干扰机）；在右侧 → 推向左。
     * 始终把导弹引向**远离干扰机自身**的一侧，避免偏移方向朝向干扰机而把导弹吸向车体。</p>
     *
     * <p>偏移幅度 = 兜底基准（{@code jammingOffsetBaseBlocks}）与
     * 瞄准点距离×tan(干扰角)（{@code jammingOffsetAngleDeg}）中的较大值 × 干扰强度，
     * 均由干扰机 JSON 配置（{@code offset_base} / {@code offset_angle}）。</p>
     * 服务端执行（SACLOS 制导评估仅在服务端运行）。
     */
    private static Vec3 applyJamming(RVP_BaseBullet projectile, Vec3 dir, Vec3 point, double strength) {
        if (strength <= 0.0) {
            projectile.jammingOffsetDir = Vec3.ZERO;
            return point;
        }
        double distToPoint = projectile.position().distanceTo(point);
        double angular = distToPoint * Math.tan(Math.toRadians(projectile.jammingOffsetAngleDeg));
        double wobble = Math.max(projectile.jammingOffsetBaseBlocks, angular) * strength;
        // 直接修改速度方向分量（远离干扰机一侧），与切断制导分支共用同一偏转。
        applyVelocityRotation(projectile, dir, strength);
        double side = projectile.jammingSideSign;
        if (side == 0.0) {
            side = 1.0;
        }
        Vec3 horizontal = buildHorizontalPerp(dir).scale(side).scale(wobble);
        // 叠加向下的偏移分量，与横向偏移合成「左下/右下」斜向拉偏（朝远离干扰机 + 下坠）。
        Vec3 down = new Vec3(0.0, -projectile.jammingOffsetDownBlocks * strength, 0.0);
        Vec3 offset = horizontal.add(down);
        projectile.jammingOffsetDir = offset;
        return point.add(offset);
    }

    /**
     * 直接修改速度方向分量：被干扰期间每 tick 把导弹水平速度方向向「远离干扰机」一侧
     * 旋转 {@code projectile.jammingHeadingRate}×强度 度（由干扰机 JSON 配置 {@code heading_rate}，
     * 默认 2°/tick ≈ 40°/秒）。该修改发生在 applyIntent 读取当前速度（blend 基准）之前，
     * 制导的平滑转向（blend factor）单 tick 无法抵消，导弹持续横向偏航。
     * 保持水平速度大小与垂直分量不变。
     */
    private static void applyVelocityRotation(RVP_BaseBullet projectile, Vec3 dir, double strength) {
        if (strength <= 0.0) {
            return;
        }
        double side = projectile.jammingSideSign;
        if (side == 0.0) {
            side = 1.0;
        }
        Vec3 vel = projectile.getDeltaMovement();
        double hSpeed = Math.hypot(vel.x, vel.z);
        if (hSpeed > 1.0E-6) {
            Vec3 hDir = new Vec3(vel.x, 0.0, vel.z).normalize();
            Vec3 away = buildHorizontalPerp(dir).scale(side).normalize();
            double theta = Math.toRadians(Math.min(projectile.jammingHeadingRate * strength, JAM_HEADING_MAX_DEG));
            Vec3 hNew = hDir.scale(Math.cos(theta)).add(away.scale(Math.sin(theta)));
            if (hNew.lengthSqr() > 1.0E-8) {
                hNew = hNew.normalize().scale(hSpeed);
                projectile.setDeltaMovement(hNew.x, vel.y, hNew.z);
            }
        }
    }

    /** 生成与 {@code dir} 垂直的水平方向单位向量（dir 竖直时退化为正 x 方向）。 */
    private static Vec3 buildHorizontalPerp(Vec3 dir) {
        Vec3 horizontalDir = new Vec3(dir.x, 0.0, dir.z);
        if (horizontalDir.lengthSqr() <= 1.0E-8) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        Vec3 perp = horizontalDir.normalize().cross(up);
        return perp.lengthSqr() <= 1.0E-8 ? new Vec3(1.0, 0.0, 0.0) : perp.normalize();
    }

    /**
     * Generate a random vector perpendicular to {@code dir} with magnitude up to {@code wobble}.
     */
    private static Vec3 randomPerpVector(Vec3 dir, RVP_BaseBullet projectile, double wobble) {
        Vec3 perp1 = buildPerp1(dir);
        Vec3 perp2 = dir.cross(perp1).normalize();
        double a = (projectile.level().random.nextDouble() - 0.5) * 2.0 * wobble;
        double b = (projectile.level().random.nextDouble() - 0.5) * 2.0 * wobble;
        return perp1.scale(a).add(perp2.scale(b));
    }

    private static Vec3 buildPerp1(Vec3 dir) {
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        Vec3 perp1 = dir.cross(up);
        if (perp1.lengthSqr() < 1.0E-8) {
            perp1 = new Vec3(1.0, 0.0, 0.0);
        }
        return perp1.normalize();
    }

    private static Vec3 closestPointOnRay(Vec3 rayOrigin, Vec3 rayDir, Vec3 point) {
        Vec3 toPoint = point.subtract(rayOrigin);
        double t = Math.max(toPoint.dot(rayDir), 0.0);
        return rayOrigin.add(rayDir.scale(t));
    }
}
