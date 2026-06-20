package org.ywzj.rvp.client.lead;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

public final class RVP_MachinegunLeadSolver {
    private static final double MAX_SOLVE_TICKS = 120.0;
    private static final double SOLVE_STEP_TICKS = 0.5;
    private static final int AIM_REFINE_ITERATIONS = 6;

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
        Vec3 targetPos = interpolateEntityCenter(target, partialTick);
        Vec3 targetVelocity = estimateEntityVelocity(target);
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
        double acceptableMiss = Math.max(Math.max(target.getBbWidth(), target.getBbHeight()) * 0.8, 1.25);
        RVP_LeadSolution best = null;

        for (double timeTicks = 1.0; timeTicks <= maxTicks; timeTicks += SOLVE_STEP_TICKS) {
            Vec3 futureTargetPos = targetPos.add(targetVelocity.scale(timeTicks));
            Vec3 aimPoint = futureTargetPos;

            for (int i = 0; i < AIM_REFINE_ITERATIONS; i++) {
                Vec3 aimDir = aimPoint.subtract(muzzle);
                if (aimDir.lengthSqr() < 1.0E-6) {
                    break;
                }
                Vec3 bulletPos = simulateBulletPosition(
                        muzzle,
                        aimDir.normalize(),
                        inheritedVelocity,
                        muzzleSpeed,
                        gravity,
                        friction,
                        timeTicks
                );
                Vec3 error = futureTargetPos.subtract(bulletPos);
                aimPoint = aimPoint.add(error);
                if (error.lengthSqr() < 1.0E-3) {
                    break;
                }
            }

            Vec3 finalAimDir = aimPoint.subtract(muzzle);
            if (finalAimDir.lengthSqr() < 1.0E-6) {
                continue;
            }
            Vec3 finalBulletPos = simulateBulletPosition(
                    muzzle,
                    finalAimDir.normalize(),
                    inheritedVelocity,
                    muzzleSpeed,
                    gravity,
                    friction,
                    timeTicks
            );
            double missDistance = finalBulletPos.distanceTo(futureTargetPos);
            if (best == null || missDistance < best.missDistance()) {
                best = new RVP_LeadSolution(target, targetPos, aimPoint, timeTicks, missDistance);
            }
            if (missDistance <= acceptableMiss) {
                return new RVP_LeadSolution(target, targetPos, aimPoint, timeTicks, missDistance);
            }
        }
        if (best != null && best.missDistance() <= acceptableMiss * 2.0) {
            return best;
        }
        return null;
    }

    private static Vec3 simulateBulletPosition(Vec3 muzzle, Vec3 aimDir, Vec3 inheritedVelocity,
                                               double muzzleSpeed, double gravity, double friction,
                                               double timeTicks) {
        Vec3 position = muzzle;
        Vec3 velocity = aimDir.scale(muzzleSpeed).add(inheritedVelocity);
        int fullTicks = Mth.floor(timeTicks);
        double partialTick = timeTicks - fullTicks;

        for (int tick = 0; tick < fullTicks; tick++) {
            position = position.add(velocity);
            velocity = velocity.scale(1.0 - friction).add(0.0, -gravity, 0.0);
        }
        if (partialTick > 1.0E-6) {
            position = position.add(velocity.scale(partialTick));
        }
        return position;
    }

    @Nullable
    private static RVP_WeaponData resolveCurrentWeaponData(WeaponUnit weaponUnit) {
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
    private static Entity resolveTrackedTarget(WeaponUnit weaponUnit) {
        Entity target = weaponUnit.getLockedEntity();
        if (target != null && target.isAlive()) {
            return target;
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
        return tickDelta.lerp(motion, 0.65);
    }
}
