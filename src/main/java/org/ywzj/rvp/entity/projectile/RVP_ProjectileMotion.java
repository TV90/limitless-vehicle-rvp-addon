package org.ywzj.rvp.entity.projectile;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.debug.RVP_DualPulseDebug;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleSolution;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PropulsionMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_QuadraticAirDrag;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 弹道与朝向：直接对齐 {@link org.ywzj.vehicle.entity.weapon.BulletEntity} 与
 * {@link org.ywzj.vehicle.entity.weapon.MissileEntity#tickMove()}，供 RVP 全弹种复用。
 */
public final class RVP_ProjectileMotion {

    private static final double MISSILE_COAST_LERP = 0.2D;

    private RVP_ProjectileMotion() {}

    /** 只在导弹且显式请求攻角时接管姿态；瞬转豁免由共用参数快照判定。 */
    public static boolean usesAttackAngle(RVP_BaseBullet projectile) {
        if (projectile == null || !projectile.isMissile() || projectile.smartFuseActive) return false;
        // 调用本项目双端配置出口，远程渲染克隆同样可按已同步的武器配置判断姿态门控。
        RVP_WeaponData config = projectile.getResolvedWeaponConfig();
        if (config == null) return false;
        var data = config.getProjectileData();
        // 调用本项目字段访问器，未启用时提前返回，避免在默认运动热路径反复分配快照。
        if (!data.isRvpAeroSteering() || data.getRvpAttackAngleLimitDeg() <= 0.0) return false;
        // 调用本项目数据解析器，区间切换时按当前飞行 Tick 重新判断，不缓存武器 ID。
        Float factor = data.resolveTurningFactor(projectile.getFlightTickCount());
        return data.resolveAeroSteeringLimits(config.getProjectileVelocity(),
                projectile.getY(), factor == null ? 0.5F : factor).attackAngleEnabled();
    }

    /** 写回独立姿态和载荷；返回速度供原制导链在碰撞检测前使用。 */
    public static Vec3 applyAttackAngleSteering(RVP_BaseBullet projectile, Vec3 current,
                                               Vec3 desired, RVP_AeroSteeringLimits limits) {
        // 调用本项目纯数学攻角模型，用同步姿态作为跨 Tick 机体轴。
        RVP_AttackAngleSolution solution = RVP_AttackAngleModel.solve(
                current, projectile.getLookAngle(), desired, limits);
        // 调用本体向量工具把独立机体轴写入现有俯仰/偏航同步通道。
        Vec2 rotation = VectorUtil.vecToRot(solution.bodyDirection());
        projectile.setXRot(rotation.x);
        projectile.setYRot(rotation.y);
        // 调用本项目载荷与单步标记入口，避免本 Tick 无目标兜底重复积分。
        projectile.recordAeroLoadFactor(solution.loadFactor());
        projectile.markAttackAngleApplied();
        return solution.velocity();
    }

    /** 丢锁、延迟制导或无目标时仍推进残留攻角，姿态逐步向当前速度回正。 */
    public static void finishAttackAngleGuidance(RVP_BaseBullet projectile) {
        // 调用本项目门控和单步标记，确保每个真实飞行 Tick 最多求解一次。
        if (!usesAttackAngle(projectile) || projectile.hasAttackAngleApplied()) return;
        var data = projectile.rvpData.getProjectileData();
        Float factor = data.resolveTurningFactor(projectile.getFlightTickCount());
        RVP_AeroSteeringLimits limits = data.resolveAeroSteeringLimits(
                projectile.rvpData.getProjectileVelocity(), projectile.getY(), factor == null ? 0.5F : factor);
        Vec3 velocity = projectile.getDeltaMovement();
        // 调用本项目实体适配器，保留独立姿态并按残余攻角转向。
        projectile.setDeltaMovement(applyAttackAngleSteering(projectile, velocity, velocity, limits));
    }

    /** 干扰强制改变速度后，在制导修正前记录实际攻角，防止大载荷被后续小修正覆盖。 */
    public static void recordAttackAngleDeflection(RVP_BaseBullet projectile) {
        // 调用本项目门控，普通弹继续沿用自己的干扰行为。
        if (!usesAttackAngle(projectile)) return;
        // 调用本项目攻角载荷测量和载荷合并入口，本函数不额外旋转速度。
        projectile.recordAeroLoadFactor(RVP_AttackAngleModel.loadFactor(projectile.getDeltaMovement(),
                projectile.getLookAngle(), projectile.rvpData.getProjectileData().getRvpAttackAngleLimitDeg()));
    }

    /**
     * {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()} 运动与朝向（含 lerp）。
     */
    public static void tickCannonBullet(RVP_BulletEntity bullet) {
        Vec3 movement = bullet.getDeltaMovement();
        bullet.applyCannonFacingFromVelocity(movement, true);

        double mx = movement.x;
        double my = movement.y;
        double mz = movement.z;
        double nextPosX = bullet.getX() + mx;
        double nextPosY = bullet.getY() + my;
        double nextPosZ = bullet.getZ() + mz;
        bullet.setPos(nextPosX, nextPosY, nextPosZ);
        bullet.flightDistance += movement.length();

        float friction = bullet.cannonFriction;
        float gravity = bullet.cannonGravity;
        if (bullet.isInWater()) {
            friction = 0.4F;
            gravity *= 0.6F;
        }
        bullet.setDeltaMovement(bullet.getDeltaMovement().scale(1f - friction));
        bullet.setDeltaMovement(bullet.getDeltaMovement().add(0, -gravity, 0));
        // 调用本项目弹体速率状态入口，使炮弹当前基准跟随摩擦与重力后的权威速度。
        bullet.updateFlightSpeedState(bullet.getDeltaMovement());
    }

    /**
     * {@link org.ywzj.vehicle.entity.weapon.MissileEntity#tickMove()} — 推力沿 {@code getLookAngle()}，
     * 点火后无目标时按速度归正朝向；RVP 在 {@code rotate_to_motion} 时每 tick 将朝向对齐速度后再推力。
     */
    public static void tickMissileMove(RVP_BaseBullet projectile) {
        RVP_WeaponData data = projectile.rvpData;
        if (data == null) {
            return;
        }

        Vec3 velocity = projectile.getDeltaMovement();
        int ignition = resolveMotorIgnitionTick(projectile, data);

        if (projectile.getFlightTickCount() >= ignition) {
            if (!usesAttackAngle(projectile) && data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                applyMissileCoastFacing(projectile, velocity, 1.0F);
            }
            Vec3 lookDir = projectile.getLookAngle();
            int motorTick = projectile.getFlightTickCount() - ignition;
            float burn1 = data.getResolvedMotorBurnTime();
            boolean burning1 = motorTick <= burn1;
            boolean burning2 = false;
            if (!burning1
                    && projectile.isMissile()
                    && data.getProjectileData().usesSecondPulse()
                    && isDualPulseSupportedMissile(data)) {
                if (projectile.secondPulseStartTick < 0 && shouldStartSecondPulse(projectile, data, velocity)) {
                    projectile.secondPulseStartTick = projectile.getFlightTickCount();
                    projectile.getEntityData().set(RVP_BaseBullet.DATA_SECOND_PULSE_START_TICK, projectile.secondPulseStartTick);
                    projectile.getEntityData().set(
                            RVP_BaseBullet.DATA_SECOND_PULSE_BURN_TIME_TICK,
                            Math.round(data.getProjectileData().getResolvedSecondPulseBurnTime())
                    );
                    RVP_DualPulseDebug.noteSecondPulseStarted(projectile, data);
                }
                if (projectile.secondPulseStartTick >= 0) {
                    int t2 = projectile.getFlightTickCount() - projectile.secondPulseStartTick;
                    burning2 = t2 >= 0 && t2 <= data.getProjectileData().getResolvedSecondPulseBurnTime();
                }
            }
            // 调用本项目推进工具：实体导弹统一解析主燃烧、第二脉冲和滑翔阶段的推力与质量。
            RVP_PropulsionMath.MotorState motorState = RVP_PropulsionMath.resolveMotorState(
                    data.getProjectileData(), motorTick, burning2);
            if (motorState.burning()) {
                // 调用本项目推进工具：沿弹体朝向积分当前发动机阶段的推力增量。
                velocity = RVP_PropulsionMath.applyThrust(velocity, lookDir, motorState, 1.0D);
            }
            // 调用本项目统一空气阻力工具：按当前质量、速度平方及弹体高度倍率扣速。
            velocity = RVP_QuadraticAirDrag.apply(velocity, data.getResolvedDragCoefficient(), motorState.mass(),
                    resolveMissileAltitudeDragFactor(projectile, data));
        }

        if (projectile.getFlightTickCount() < ignition) {
            velocity = applyPreIgnitionVelocity(projectile, velocity, ignition);
        } else {
            // 施加沿y轴向下的PhysicsEngine.G 武器配置里面gravity设置为0也不能避免
            velocity = applyPropulsionGravity(projectile, velocity, data);
        }

        velocity = clampSpeed(projectile, velocity, data);
        // 调用本项目诱导阻力结算，在速度上下限钳制后扣除本 Tick 转向能量代价。
        velocity = applyInducedDrag(projectile, velocity, data);
        if (projectile.isMissile() && data.getProjectileData().isConstantSpeed()
                && velocity.lengthSqr() > 1.0E-12) {
            // 调用本项目弹体速率基准入口，使恒速导弹在推力/阻力结算后恢复至已达到的峰值。
            velocity = velocity.normalize().scale(Math.max(
                    projectile.getMotionSpeedReference(), velocity.length()));
            velocity = clampSpeed(projectile, velocity, data);
        }
        projectile.setDeltaMovement(velocity);
        projectile.setPos(projectile.position().add(velocity));
        projectile.flightDistance += velocity.length();
        // 调用本项目弹体速率状态入口，分离当前速率与只增不减的历史峰值。
        projectile.updateFlightSpeedState(velocity);

        // 攻角模式已由制导阶段更新姿态，运动结束不得重新对齐速度。
        if (!usesAttackAngle(projectile) && projectile.getFlightTickCount() >= ignition) {
            int motorTick = projectile.getFlightTickCount() - ignition;
            boolean coasting = motorTick > data.getResolvedMotorBurnTime()
                    && (projectile.secondPulseStartTick < 0
                        || projectile.getFlightTickCount() - projectile.secondPulseStartTick > data.getProjectileData().getResolvedSecondPulseBurnTime())
                    && projectile.getTargetEntity() == null
                    && projectile.getTargetPos() == null;
            if (data.getProjectileData().isRotateToMotion()) {
                if (coasting && velocity.lengthSqr() > 0.01) {
                    applyMissileCoastFacing(projectile, velocity, (float) MISSILE_COAST_LERP);
                } else if (velocity.lengthSqr() > 1.0E-6) {
                    applyMissileCoastFacing(projectile, velocity, 1.0F);
                }
            } else if (coasting && velocity.lengthSqr() > 0.01) {
                applyMissileCoastFacing(projectile, velocity, (float) MISSILE_COAST_LERP);
            }
        }
    }

    /**
     * 二级脉冲支持的制导类型白名单：IR/ARH/SARH/ARM（空空弹传统双脉冲）+ GPS
     * （弹道/准弹道导弹的两级推进，如 9M723 一级助推 + 二级脉冲接力；2026-09-15 应用户要求加入）。
     * 门控仅在 {@code second_pulse} 显式配置时生效——未配置该字段的 GPS 导弹行为不变。
     */
    private static boolean isDualPulseSupportedMissile(RVP_WeaponData data) {
        return data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARM)
                || data.usesGuidanceType(RVP_EnumGuidanceType.GPS);
    }

    private static boolean shouldStartSecondPulse(RVP_BaseBullet projectile, RVP_WeaponData data, Vec3 velocity) {
        int ignition = resolveMotorIgnitionTick(projectile, data);
        int motorTick = projectile.getFlightTickCount() - ignition;
        if (motorTick <= data.getResolvedMotorBurnTime()) {
            return false;
        }

        float speedThreshold = data.getProjectileData().getResolvedSecondPulseTriggerSpeed();
        float distThreshold = data.getProjectileData().getResolvedSecondPulseTriggerDistance();
        boolean speedEnabled = speedThreshold > 0f;
        boolean distEnabled = distThreshold > 0f;

        boolean speedOk = false;
        if (speedEnabled) {
            speedOk = velocity.length() <= speedThreshold;
        }

        boolean distOk = false;
        double resolvedDistance = -1D;
        String distanceSource = null;
        Entity target = null;
        Vec3 targetPos = null;
        Vec3 lastGuidancePos = projectile.getLastGuidancePos();
        if (distEnabled) {
            target = projectile.getTargetEntity();
            if (target != null && target.isAlive()) {
                targetPos = projectile.aimPoint(target);
                resolvedDistance = projectile.position().distanceTo(targetPos);
                distanceSource = "targetEntity";
                distOk = resolvedDistance <= distThreshold;
            } else {
                targetPos = projectile.getTargetPos();
                if (targetPos == null) {
                    targetPos = lastGuidancePos;
                    if (targetPos != null) {
                        distanceSource = "lastGuidancePos";
                    }
                } else {
                    distanceSource = "targetPos";
                }
                if (targetPos != null) {
                    resolvedDistance = projectile.position().distanceTo(targetPos);
                    distOk = resolvedDistance <= distThreshold;
                }
            }
        }

        RVP_DualPulseDebug.noteEvaluation(
                projectile,
                data,
                velocity,
                ignition,
                motorTick,
                data.getResolvedMotorBurnTime(),
                speedEnabled,
                speedThreshold,
                speedOk,
                distEnabled,
                distThreshold,
                distOk,
                target,
                targetPos,
                lastGuidancePos,
                resolvedDistance,
                distanceSource
        );

        return speedOk || distOk;
    }

    /** 发射完成：初速 + 可选载机速度已写入 {@code deltaMovement}。 */
    public static void finalizeSpawnOrientation(RVP_BaseBullet projectile, RVP_BaseBullet.AimRot aim) {
        Vec3 vel = projectile.getDeltaMovement();
        // 调用本项目弹体速率状态入口，吸收载机速度叠加后的最终出膛速度。
        projectile.updateFlightSpeedState(vel);
        if (projectile.usesCannonBallistics()) {
            if (vel.lengthSqr() > 1.0E-8) {
                projectile.applyCannonFacingFromVelocity(vel, false);
            } else {
                projectile.applySpawnAimRot(aim);
            }
        } else {
            projectile.applySpawnAimRot(aim);
            if (projectile.rvpData != null
                    && !usesAttackAngle(projectile)
                    && projectile.rvpData.getProjectileData().isRotateToMotion()
                    && vel.lengthSqr() > 1.0E-6) {
                applyMissileCoastFacing(projectile, vel, 1.0F);
            }
        }
        projectile.yRotO = projectile.getYRot();
        projectile.xRotO = projectile.getXRot();
    }

    /**
     * 导弹无目标惯性段：{@link org.ywzj.vehicle.entity.weapon.MissileEntity#tickMove()} 归正公式。
     */
    public static void applyMissileCoastFacing(Entity entity, Vec3 velocity, float lerpFactor) {
        if (velocity.lengthSqr() <= 1.0E-6) {
            return;
        }
        Vec3 norm = velocity.normalize();
        double pitch = Math.toDegrees(-Math.asin(Mth.clamp(norm.y, -1.0, 1.0)));
        double yaw = Math.toDegrees(Math.atan2(norm.z, norm.x)) - 90.0;
        entity.setXRot((float) Mth.lerp(lerpFactor, entity.getXRot(), pitch));
        entity.setYRot((float) Mth.lerp(lerpFactor, entity.getYRot(), yaw));
    }

    /** 制导改速后朝向：与 {@link org.ywzj.vehicle.entity.weapon.MissileEntity} 转向一致，用 {@link VectorUtil#vecToRot}。 */
    public static void applyGuidanceFacing(Entity entity, Vec3 direction) {
        // 调用本项目攻角门控，防止普通制导出口覆盖刚写回的独立机体轴。
        if (entity instanceof RVP_BaseBullet projectile && usesAttackAngle(projectile)) return;
        if (direction.lengthSqr() <= 1.0E-8) {
            return;
        }
        Vec2 rot = VectorUtil.vecToRot(direction);
        entity.setXRot(rot.x);
        entity.setYRot(rot.y);
    }

    public static void applyRotationFromVelocity(RVP_BaseBullet projectile, Vec3 velocity) {
        // 调用本项目攻角门控；穿透减速等外力只改速度，姿态留待下次求解。
        if (usesAttackAngle(projectile)) return;
        if (velocity.lengthSqr() <= 1.0E-6) {
            return;
        }
        RVP_WeaponData data = projectile.rvpData;
        if (data == null || !data.getProjectileData().isRotateToMotion()) {
            return;
        }
        if (projectile.usesCannonBallistics()) {
            projectile.applyCannonFacingFromVelocity(velocity, false);
        } else {
            applyMissileCoastFacing(projectile, velocity, 1.0F);
        }
    }

    /**
     * TV / HITL MOUSE flight: speed along {@link RVP_BaseBullet#getLookAngle()}, no {@code rotate_to_motion}.
     */
    public static void tickHitlTvMove(RVP_MissileEntity missile) {
        // 调用本项目普通运动入口：攻角直控不再把速度强制投到视线，双脉冲也走统一推进链。
        if (usesAttackAngle(missile)) {
            if (missile.rvpData.usesPropulsion()) tickMissileMove(missile);
            else missile.tickBallisticMotion();
            return;
        }
        RVP_WeaponData data = missile.rvpData;
        if (data == null) {
            return;
        }
        Vec3 velocity = missile.getDeltaMovement();
        Vec3 lookDir = missile.getLookAngle();
        int ignition = resolveMotorIgnitionTick(missile, data);

        if (missile.getFlightTickCount() < ignition) {
            velocity = applyPreIgnitionVelocity(missile, velocity, ignition);
        } else {
            double speed = Math.max(velocity.length(), missile.getMotionSpeedReference());
            int motorTick = missile.getFlightTickCount() - ignition;
            // 调用本项目推进工具：HITL 导弹复用主燃烧与滑翔阶段的质量、推力解析。
            RVP_PropulsionMath.MotorState motorState = RVP_PropulsionMath.resolveMotorState(
                    data.getProjectileData(), motorTick, false);
            // 调用本项目推进工具：沿操作手视线方向积分当前主发动机推力。
            Vec3 thrustAdjustedVelocity = RVP_PropulsionMath.applyThrust(
                    lookDir.scale(speed), lookDir, motorState, 1.0D);
            speed = Math.max(thrustAdjustedVelocity.length(), 0.01D);
            thrustAdjustedVelocity = lookDir.scale(speed);
            // 调用本项目统一空气阻力工具：HITL 沿视线方向应用与自动导弹相同的质量相关阻力。
            Vec3 dragAdjustedVelocity = RVP_QuadraticAirDrag.apply(thrustAdjustedVelocity,
                    data.getResolvedDragCoefficient(), motorState.mass(),
                    resolveMissileAltitudeDragFactor(missile, data));
            speed = Math.max(dragAdjustedVelocity.length(), 0.01D);
            if (data.getProjectileData().isConstantSpeed()) {
                speed = Math.max(missile.getMotionSpeedReference(), data.getProjectileVelocity());
            }
            velocity = lookDir.scale(speed);
            velocity = applyPropulsionGravity(missile, velocity, data);
            velocity = clampSpeed(missile, velocity, data);
        }

        // 调用本项目诱导阻力结算，使 HITL/线导直控与自动制导使用同一能量代价。
        velocity = applyInducedDrag(missile, velocity, data);
        if (data.getProjectileData().isConstantSpeed() && velocity.lengthSqr() > 1.0E-12) {
            // 调用本项目弹体速率基准入口，使恒速 HITL 导弹无论点火阶段都按已达峰值恢复速度。
            velocity = velocity.normalize().scale(Math.max(
                    missile.getMotionSpeedReference(), velocity.length()));
            velocity = clampSpeed(missile, velocity, data);
        }
        missile.setDeltaMovement(velocity);
        missile.setPos(missile.position().add(velocity));
        missile.flightDistance += velocity.length();
        // 调用本项目弹体速率状态入口，使 HITL 转向损失不会在下一 Tick 被历史峰值回填。
        missile.updateFlightSpeedState(velocity);
    }

    /** 运动学生效的点火 Tick = max(ignition_delay_tick, 冷发射时长)，冷发射窗口整体覆盖到点火。 */
    public static int resolveMotorIgnitionTick(RVP_BaseBullet projectile, RVP_WeaponData data) {
        if (projectile == null || data == null) {
            return 0;
        }
        return Math.max(data.getResolvedIgnitionDelayTick(), projectile.getColdLaunchTimeTick());
    }

    private static Vec3 applyPreIgnitionVelocity(RVP_BaseBullet projectile, Vec3 velocity, int ignition) {
        AbstractVehicle carrier = projectile.shooterVehicle;
        if (carrier == null) {
            return velocity;
        }
        int coldLaunchTick = projectile.getColdLaunchTimeTick();
        // 读取冷发射窗口内的 PRESET 接管标记，使单 Tick 的 GPS 源抖动不会把弹体掰回竖直。
        boolean presetGuidanceApplied = projectile.hasPresetGuidanceMotionAppliedDuringLaunch();
        if (shouldPreserveGuidanceDuringColdLaunch(
                projectile.getFlightTickCount(), coldLaunchTick,
                projectile.rvpData.getResolvedIgnitionDelayTick(), presetGuidanceApplied)) {
            // 当前速度已经由 PRESET 制导写入；保留其水平分量，避免冷发射配置每 Tick 将弹体掰回竖直。
            return velocity;
        }
        Vector3f[] axes = carrier.getMainCubeOBB().obb().getAxes();
        if (coldLaunchTick > 0 && projectile.getFlightTickCount() < coldLaunchTick) {
            Vec3 configured = projectile.getColdLaunchVelocity();
            Vec3 launchVelocity = new Vec3(axes[0]).scale(configured.x)
                    .add(new Vec3(axes[1]).scale(configured.y))
                    .add(new Vec3(axes[2]).scale(configured.z));
            return carrier.getDeltaMovement().add(launchVelocity);
        }
        Vec3 eject = new Vec3(axes[1].negate());
        float muzzle = projectile.rvpData.getProjectileVelocity();
        if (muzzle > 1.0E-4f) {
            if (projectile.getFlightTickCount() == 0) {
                return velocity.add(eject);
            }
            return velocity;
        }
        return carrier.getDeltaMovement().add(eject);
    }

    /**
     * 判断冷发射运动是否应让位给当前 Tick 的 PRESET 制导速度。
     *
     * <p>只有冷发射时长覆盖发动机点火延迟时才启用接管。这样 YJ-20 这类
     * {@code cold_launch_time_tick == ignition_delay_tick} 的弹体可以在冷发射末段转向，
     * 而伊斯坎德尔等冷发射更短的弹体继续沿原有冷发射速度运行。</p>
     *
     * @param flightTick 当前飞行 Tick
     * @param coldLaunchTick 冷发射持续 Tick 数
     * @param ignitionDelayTick 配置的发动机点火延迟 Tick 数
     * @param guidanceApplied 当前冷发射窗口是否已经写入过 PRESET 制导速度
     * @return 是否保留制导层刚写入的速度
     */
    static boolean shouldPreserveGuidanceDuringColdLaunch(
            int flightTick,
            int coldLaunchTick,
            int ignitionDelayTick,
            boolean guidanceApplied
    ) {
        return guidanceApplied
                && coldLaunchTick > 0
                && flightTick < coldLaunchTick
                && coldLaunchTick >= Math.max(ignitionDelayTick, 0);
    }

    private static Vec3 applyPropulsionGravity(RVP_BaseBullet projectile, Vec3 velocity, RVP_WeaponData data) {
        if (projectile.isInWater()) {
            float waterGravity = data.getGravityInWater();
            if (waterGravity != 0f) {
                return velocity.add(0, waterGravity, 0);
            }
            return velocity.subtract(0, PhysicsEngine.G * 0.6f, 0);
        }
        float gravity = data.getGravity();
//        if (gravity != 0f) {
        // 不再对rvp:missile导弹默认施加 - PhysicsEngine.G
        return velocity.add(0, gravity, 0);
//        }
//        return velocity.subtract(0, PhysicsEngine.G, 0);
    }

    static float resolveMissileAltitudeDragFactor(RVP_BaseBullet projectile, RVP_WeaponData data) {
        if (projectile == null || data == null || !projectile.isMissile()) {
            return 1.0f;
        }
        return data.getProjectileData().resolveAltitudeDragFactor(projectile.getY());
    }

    public static Vec3 clampSpeed(RVP_BaseBullet projectile, Vec3 velocity, RVP_WeaponData data) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        float min = data.getProjectileData().getMinSpeed();
        // 高度分层极速：实际极速 = max_speed × 当前高度倍率（未配置倍率表时恒 1.0，行为不变）
        float max = data.getProjectileData().getMaxSpeed();
        if (max > 0f && projectile.isMissile()) {
            max = (float) (max * data.getProjectileData().resolveAltitudeMaxSpeedFactor(projectile.getY()));
        }
        if (max > 0f && min > 0f && min > max) {
            min = 0f;
        }
        if (max > 0f && speed > max) {
            return velocity.normalize().scale(max);
        }
        if (min > 0f && speed < min) {
            return velocity.normalize().scale(min);
        }
        return velocity;
    }

    /**
     * 在推力、基础阻力、重力和速度钳制之后结算气动转向诱导阻力。
     *
     * <p>{@code constant_speed=true} 明确表示保持配置速率，因此跳过该独立损失。阶段 S2
     * 默认关闭气动模型，旧配置不会进入本分支。</p>
     *
     * @param projectile 当前弹体，用于读取本 Tick 的载荷因子
     * @param velocity 已完成速度钳制的速度
     * @param data 当前武器配置
     * @return 扣除诱导阻力后的速度
     */
    static Vec3 applyInducedDrag(RVP_BaseBullet projectile, Vec3 velocity, RVP_WeaponData data) {
        if (projectile == null || data == null || velocity == null) {
            return velocity;
        }
        // 调用本项目弹体数据访问器，确认气动开关与恒速豁免后再解析损失系数。
        var projectileData = data.getProjectileData();
        if (!projectileData.isRvpAeroSteering() || projectileData.isConstantSpeed()) {
            return velocity;
        }
        double loadFactor = projectile.getAeroLoadFactor();
        double speed = velocity.length();
        // 调用本项目诱导阻力系数解析器，显式配置优先，未配置时复用有效基础阻力。
        double inducedDrag = projectileData.resolveInducedDragCoefficient();
        // 调用本项目纯数学公式，按 λ² 计算本 Tick 应扣除的速率。
        double loss = RVP_AeroSteeringModel.inducedDragLoss(
                inducedDrag, loadFactor, speed);
        if (loss <= 0.0 || speed <= 1.0E-8) {
            return velocity;
        }
        double minimumSpeed = Math.max(projectileData.getMinSpeed(), 0.01);
        // 调用本项目攻角门控，新分支与虚拟积分一致：阻力不可反向加速极低速弹体。
        if (usesAttackAngle(projectile)) minimumSpeed = Math.min(speed, minimumSpeed);
        return velocity.normalize().scale(Math.max(speed - loss, minimumSpeed));
    }
}
