package org.ywzj.rvp.client.lead;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

public final class RVP_MachinegunLeadSolver {
    /** 单次提前量解算允许搜索的最长飞行时间，单位为 tick。 */
    private static final double MAX_SOLVE_TICKS = 120.0;

    /** 候选命中时间的扫描步长，单位为 tick。 */
    private static final double SOLVE_STEP_TICKS = 0.5;

    /** 每个候选命中时间最多执行的炮口方向误差修正次数。 */
    private static final int AIM_REFINE_ITERATIONS = 6;

    /** 小于该速度的目标按静止处理，避免载具物理微抖产生伪提前量，单位为格/tick。 */
    static final double TARGET_VELOCITY_DEADBAND = 0.01D;

    /** 目标速度死区的平方，避免每次判定执行开方。 */
    private static final double TARGET_VELOCITY_DEADBAND_SQR =
            TARGET_VELOCITY_DEADBAND * TARGET_VELOCITY_DEADBAND;

    /** 单次弹道积分结果：包含预测位置与累计飞行距离。 */
    private record BulletSimResult(Vec3 position, double travelledDistance) {}

    private RVP_MachinegunLeadSolver() {}

    public static boolean isCurrentRvpMachinegun(WeaponUnit weaponUnit) {
        return resolveCurrentWeaponData(weaponUnit) != null;
    }

    @Nullable
    public static RVP_LeadSolution solveCurrent(WeaponUnit weaponUnit, float partialTick) {
        RVP_WeaponData data = resolveCurrentWeaponData(weaponUnit);
        if (data == null) {
            return null;
        }
        Entity target = resolveTrackedTarget(weaponUnit);
        if (target == null) {
            return null;
        }
        Vec3 muzzle = weaponUnit.aimContext().from;
        if (muzzle == null) {
            muzzle = weaponUnit.worldCurrentBoltPosition();
        }
        return solveForTarget(weaponUnit, data, muzzle, target, partialTick);
    }

    @Nullable
    public static RVP_LeadSolution solveForTarget(WeaponUnit weaponUnit, RVP_WeaponData data, Vec3 muzzle,
                                                  Entity target, float partialTick) {
        if (weaponUnit == null || data == null || target == null || muzzle == null) {
            return null;
        }
        Vec3 targetVelocity = estimateEntityVelocity(target);
        Vec3 targetPos = interpolateEntityCenter(target, partialTick);
        Vec3 inheritedVelocity = data.isInheritVehicleVelocity()
                ? weaponUnit.getVehicle().getDeltaMovement()
                : Vec3.ZERO;
        double muzzleSpeed = Math.max(data.resolveMuzzleSpeed(RVP_EnumWeaponKind.MACHINEGUN), 0.01f);
        double gravity = Math.max(data.getCannonGravity(), 0f);
        double friction = Mth.clamp(data.getCannonFriction(), 0f, 0.4f);
        double maxTicks = Mth.clamp(data.getLife() > 0 ? data.getLife() : MAX_SOLVE_TICKS, 10.0, MAX_SOLVE_TICKS);

        return solve(
                target,
                muzzle,
                targetPos,
                targetVelocity,
                inheritedVelocity,
                muzzleSpeed,
                gravity,
                friction,
                maxTicks
        );
    }

    @Nullable
    private static RVP_LeadSolution solve(Entity target, Vec3 muzzle, Vec3 targetPos, Vec3 targetVelocity,
                                          Vec3 inheritedVelocity, double muzzleSpeed, double gravity,
                                          double friction, double maxTicks) {
        RVP_LeadSolution best = null;

        for (double timeTicks = 1.0; timeTicks <= maxTicks; timeTicks += SOLVE_STEP_TICKS) {
            // 只按目标实测速率做匀速预测；不再叠加与目标朝向有关的固定距离或额外时间偏置。
            Vec3 futureTargetPos = predictTargetPosition(targetPos, targetVelocity, timeTicks);
            Vec3 aimPoint = futureTargetPos;

            for (int i = 0; i < AIM_REFINE_ITERATIONS; i++) {
                Vec3 aimDir = aimPoint.subtract(muzzle);
                if (aimDir.lengthSqr() < 1.0E-6) {
                    break;
                }
                BulletSimResult bulletState = simulateBulletPosition(
                        muzzle,
                        aimDir.normalize(),
                        inheritedVelocity,
                        muzzleSpeed,
                        gravity,
                        friction,
                        timeTicks
                );
                Vec3 error = futureTargetPos.subtract(bulletState.position());
                aimPoint = aimPoint.add(error);
                if (error.lengthSqr() < 1.0E-3) {
                    break;
                }
            }

            Vec3 finalAimDir = aimPoint.subtract(muzzle);
            if (finalAimDir.lengthSqr() < 1.0E-6) {
                continue;
            }
            BulletSimResult finalBulletState = simulateBulletPosition(
                    muzzle,
                    finalAimDir.normalize(),
                    inheritedVelocity,
                    muzzleSpeed,
                    gravity,
                    friction,
                    timeTicks
            );
            double missDistance = finalBulletState.position().distanceTo(futureTargetPos);
            if (best == null || missDistance < best.missDistance()) {
                best = new RVP_LeadSolution(
                        target,
                        targetPos,
                        aimPoint,
                        timeTicks,
                        missDistance,
                        finalBulletState.travelledDistance()
                );
            }
        }
        return best;
    }

    private static BulletSimResult simulateBulletPosition(Vec3 muzzle, Vec3 aimDir, Vec3 inheritedVelocity,
                                                          double muzzleSpeed, double gravity, double friction,
                                                          double timeTicks) {
        Vec3 position = muzzle;
        Vec3 velocity = aimDir.scale(muzzleSpeed).add(inheritedVelocity);
        double travelledDistance = 0.0D;
        int fullTicks = Mth.floor(timeTicks);
        double partialTick = timeTicks - fullTicks;

        for (int tick = 0; tick < fullTicks; tick++) {
            position = position.add(velocity);
            travelledDistance += velocity.length();
            velocity = velocity.scale(1.0 - friction).add(0.0, -gravity, 0.0);
        }
        if (partialTick > 1.0E-6) {
            position = position.add(velocity.scale(partialTick));
            travelledDistance += velocity.length() * partialTick;
        }
        return new BulletSimResult(position, travelledDistance);
    }

    @Nullable
    public static RVP_WeaponData resolveCurrentWeaponData(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().orElse(null);
        if (weapon == null || !(weapon.getData() instanceof RVP_WeaponData data)) {
            return null;
        }
        if (data.getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) {
            return null;
        }
        return data;
    }

    @Nullable
    public static Entity resolveTrackedTarget(WeaponUnit weaponUnit) {
        Entity target = weaponUnit.getLockedEntity();
        if (target != null && target.isAlive()) {
            return target;
        }
        var vehicle = org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance.vehicle;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (vehicle != null && mc.level != null) {
            Entity externalLocked = RVP_ExternalRadarLinkHelper.getClientLockedEntity(vehicle, mc.level.dimension().location());
            if (externalLocked != null && externalLocked.isAlive()) {
                return externalLocked;
            }
        }
        RadarUnit radar = weaponUnit.getMainRadarUnit();
        if (radar == null) {
            return null;
        }
        Entity radarTarget = radar.getLockedEntity();
        return radarTarget != null && radarTarget.isAlive() ? radarTarget : null;
    }

    private static Vec3 interpolateEntityCenter(Entity entity, float partialTick) {
        double x = Mth.lerp(partialTick, entity.xo, entity.getX());
        double y = Mth.lerp(partialTick, entity.yo, entity.getY());
        double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
        Vec3 centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        return new Vec3(x, y, z).add(centerOffset);
    }

    private static Vec3 estimateEntityVelocity(Entity entity) {
        Vec3 tickDelta = new Vec3(
                entity.getX() - entity.xo,
                entity.getY() - entity.yo,
                entity.getZ() - entity.zo
        );
        Vec3 motion = entity.getDeltaMovement();
        return blendAndFilterTargetVelocity(tickDelta, motion);
    }

    /**
     * 混合位置差分与实体运动速度，并对近零速度施加死区。
     * 该纯数学入口供自动化测试复核静止/低速目标行为。
     */
    static Vec3 blendAndFilterTargetVelocity(Vec3 tickDelta, Vec3 motion) {
        Vec3 safeTickDelta = tickDelta == null ? Vec3.ZERO : tickDelta;
        Vec3 safeMotion = motion == null ? Vec3.ZERO : motion;
        Vec3 blended = safeTickDelta.lerp(safeMotion, 0.65D);
        return blended.lengthSqr() < TARGET_VELOCITY_DEADBAND_SQR ? Vec3.ZERO : blended;
    }

    /** 按匀速模型计算候选时间的目标位置；目标朝向不参与提前量。 */
    static Vec3 predictTargetPosition(Vec3 targetCenter, Vec3 targetVelocity, double timeTicks) {
        Vec3 safeCenter = targetCenter == null ? Vec3.ZERO : targetCenter;
        Vec3 safeVelocity = targetVelocity == null ? Vec3.ZERO : targetVelocity;
        return safeCenter.add(safeVelocity.scale(Math.max(timeTicks, 0.0D)));
    }
}
