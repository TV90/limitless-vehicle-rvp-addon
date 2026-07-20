package org.ywzj.rvp.weapon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ext.VehicleRocketWeaponDataExt;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponIndex;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.weapon.data.VehicleRocketWeaponData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.PhysicsEngine;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.pojo.Bolt;

import java.util.ArrayList;
import java.util.List;

public final class RVP_RocketBallistics {
    public static final int DEFAULT_PREDICTION_TICK = 240;
    public static final int ARTILLERY_PREDICTION_TICK = 1200;
    private static final int MIN_SUBSTEP = 2;
    private static final int MAX_SUBSTEP = 8;
    private static final double SUBSTEP_SPEED_SCALE = 6.0D;

    private RVP_RocketBallistics() {}

    public record Params(double velocity, double gravity, double drag, int predictionTick) {}
    private record RvpState(Vec3 velocity, Vec3 lookDir, double flightSpeed, double secondPulseStartTick) {}

    @Nullable
    public static Params resolve(ResourceLocation weaponId) {
        if (weaponId == null) {
            return null;
        }
        VehicleWeaponIndex<?, ?> index = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId).orElse(null);
        if (index == null || !(index.data() instanceof VehicleRocketWeaponData data)) {
            return null;
        }
        return resolve(data);
    }

    @Nullable
    public static Params resolve(VehicleRocketWeaponData data) {
        if (!(data instanceof VehicleRocketWeaponDataExt ext) || !ext.ywzj_rvp$isBallisticEnabled()) {
            return null;
        }
        int predictionTick = Math.max(1, ext.ywzj_rvp$getBallisticPredictionTick());
        return new Params(
                Math.max(0.01, data.getVelocity()),
                Math.max(0.0, ext.ywzj_rvp$getBallisticGravity()),
                Math.max(0.0, ext.ywzj_rvp$getBallisticDrag()),
                predictionTick
        );
    }

    @Nullable
    public static Vec3 computeWeaponImpact(Level level, WeaponUnit weaponUnit, Vec3 vehicleVelocity, VehicleRocketWeaponData data, @Nullable Entity clipEntity) {
        Params params = resolve(data);
        if (params == null) {
            return null;
        }
        return computeWeaponImpact(level, weaponUnit, vehicleVelocity, params, clipEntity);
    }

    @Nullable
    public static Vec3 computeWeaponImpact(Level level, WeaponUnit weaponUnit, Vec3 vehicleVelocity, RVP_WeaponData data, @Nullable Entity clipEntity) {
        return computeWeaponImpact(level, weaponUnit, vehicleVelocity, data, clipEntity, resolvePredictionTick(data));
    }

    @Nullable
    public static Vec3 computeWeaponImpact(Level level, WeaponUnit weaponUnit, Vec3 vehicleVelocity,
                                           RVP_WeaponData data, @Nullable Entity clipEntity, int predictionTick) {
        List<AimContext> contexts = weaponUnit.aimContexts();
        if (contexts.isEmpty()) {
            contexts = List.of(weaponUnit.aimContext());
        }
        if (contexts.isEmpty()) {
            return null;
        }
        int nextIndex = resolveCurrentBoltIndex(weaponUnit, contexts.size());
        Vec3 selected = computeAimContextImpact(
                level, contexts.get(nextIndex), vehicleVelocity, data, clipEntity, predictionTick);
        if (selected != null || contexts.size() == 1) {
            return selected;
        }
        List<Vec3> impacts = new ArrayList<>();
        for (AimContext context : contexts) {
            Vec3 impact = computeAimContextImpact(
                    level, context, vehicleVelocity, data, clipEntity, predictionTick);
            if (impact != null) {
                impacts.add(impact);
            }
        }
        return averageImpact(impacts);
    }

    @Nullable
    public static Vec3 computeWeaponImpact(Level level, WeaponUnit weaponUnit, Vec3 vehicleVelocity, Params params, @Nullable Entity clipEntity) {
        List<AimContext> contexts = weaponUnit.aimContexts();
        if (contexts.isEmpty()) {
            contexts = List.of(weaponUnit.aimContext());
        }
        if (contexts.isEmpty()) {
            return null;
        }
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO || contexts.size() > 1) {
            List<Vec3> impacts = new ArrayList<>();
            for (AimContext context : contexts) {
                Vec3 impact = computeAimContextImpact(level, context, vehicleVelocity, params, clipEntity);
                if (impact != null) {
                    impacts.add(impact);
                }
            }
            return averageImpact(impacts);
        }
        int nextIndex = resolveCurrentBoltIndex(weaponUnit, contexts.size());
        return computeAimContextImpact(level, contexts.get(nextIndex), vehicleVelocity, params, clipEntity);
    }

    @Nullable
    public static Vec3 computeImpact(Level level, Vec3 startPos, Vec3 startVelocity, Params params, @Nullable Entity clipEntity) {
        Vec3 pos = startPos;
        Vec3 velocity = startVelocity;
        for (int i = 0; i < params.predictionTick(); i++) {
            int subStep = resolveSubStepCount(velocity.length());
            double dt = 1.0D / subStep;
            for (int sub = 0; sub < subStep; sub++) {
                Vec3 nextPos = pos.add(velocity.scale(dt));
                BlockHitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipEntity));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    return hit.getLocation();
                }
                pos = nextPos;
                velocity = stepVelocity(velocity, params, dt);
                if (pos.y < level.getMinBuildHeight() - 16) {
                    return null;
                }
            }
        }
        return null;
    }

    @Nullable
    public static Vec3 computeImpact(Level level, Vec3 startPos, Vec3 startVelocity, Vec3 startLookDir,
                                     RVP_WeaponData data, @Nullable Entity clipEntity) {
        return computeImpact(level, startPos, startVelocity, startLookDir, data, clipEntity,
                resolvePredictionTick(data));
    }

    @Nullable
    public static Vec3 computeImpact(Level level, Vec3 startPos, Vec3 startVelocity, Vec3 startLookDir,
                                     RVP_WeaponData data, @Nullable Entity clipEntity, int predictionTick) {
        Vec3 pos = startPos;
        Vec3 lookDir = startLookDir.lengthSqr() > 1.0E-6 ? startLookDir.normalize() : Vec3.ZERO;
        RvpState state = new RvpState(startVelocity, lookDir, Math.max(startVelocity.length(), 0.01), -1.0D);
        for (int tick = 0; tick < Math.max(predictionTick, 1); tick++) {
            // RVP_BaseBullet performs tickHit() before tickMotion(); collision must use the
            // current velocity, then physics advances the projectile for the next tick.
            Vec3 segmentEnd = pos.add(state.velocity());
            BlockHitResult hit = level.clip(new ClipContext(pos, segmentEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipEntity));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getLocation();
            }
            state = stepVelocity(state, data, tick + 1.0D, 1.0D);
            pos = pos.add(state.velocity());
            if (pos.y < level.getMinBuildHeight() - 16) {
                return null;
            }
        }
        return null;
    }

    public static Vec3 stepVelocity(Vec3 velocity, Params params) {
        return stepVelocity(velocity, params, 1.0D);
    }

    public static Vec3 stepVelocity(Vec3 velocity, Params params, double dt) {
        Vec3 next = velocity.add(0.0, -params.gravity() * dt, 0.0);
        if (params.drag() <= 0.0) {
            return next;
        }
        double speed = next.length();
        if (speed <= 1.0E-6) {
            return next;
        }
        double nextSpeed = Math.max(0.0, speed - params.drag() * dt);
        return next.scale(nextSpeed / speed);
    }

    @Nullable
    private static Vec3 computeAimContextImpact(Level level, AimContext aimContext, Vec3 vehicleVelocity, Params params, @Nullable Entity clipEntity) {
        Vec2 direction = aimContext.direction;
        Vec3 launchVelocity = VectorUtil.rotToVec(direction.x, direction.y).normalize().scale(params.velocity()).add(vehicleVelocity);
        return computeImpact(level, RVP_AimContexts.muzzle(aimContext), launchVelocity, params, clipEntity);
    }

    @Nullable
    private static Vec3 computeAimContextImpact(Level level, AimContext aimContext, Vec3 vehicleVelocity,
                                                RVP_WeaponData data, @Nullable Entity clipEntity) {
        return computeAimContextImpact(level, aimContext, vehicleVelocity, data, clipEntity,
                resolvePredictionTick(data));
    }

    @Nullable
    private static Vec3 computeAimContextImpact(Level level, AimContext aimContext, Vec3 vehicleVelocity,
                                                RVP_WeaponData data, @Nullable Entity clipEntity,
                                                int predictionTick) {
        Vec2 direction = aimContext.direction;
        Vec3 lookDir = VectorUtil.rotToVec(direction.x, direction.y).normalize();
        Vec3 launchVelocity = lookDir.scale(data.resolveMuzzleSpeed(RVP_EnumWeaponKind.ROCKET));
        if (data.isInheritVehicleVelocity()) {
            launchVelocity = launchVelocity.add(vehicleVelocity);
        }
        return computeImpact(level, RVP_AimContexts.muzzle(aimContext), launchVelocity, lookDir,
                data, clipEntity, predictionTick);
    }

    private static int resolveCurrentBoltIndex(WeaponUnit weaponUnit, int size) {
        if (size <= 1) {
            return 0;
        }
        List<Bolt> bolts = weaponUnit.getBolts();
        Bolt currentBolt = weaponUnit.getCurrentBolt();
        int index = bolts.indexOf(currentBolt);
        if (index < 0) {
            return 0;
        }
        return Math.min(index, size - 1);
    }

    @Nullable
    private static Vec3 averageImpact(List<Vec3> impacts) {
        if (impacts.isEmpty()) {
            return null;
        }
        double x = impacts.stream().mapToDouble(pos -> pos.x).average().orElse(0.0);
        double y = impacts.stream().mapToDouble(pos -> pos.y).average().orElse(0.0);
        double z = impacts.stream().mapToDouble(pos -> pos.z).average().orElse(0.0);
        return new Vec3(x, y, z);
    }

    private static int resolvePredictionTick(RVP_WeaponData data) {
        return Math.max(DEFAULT_PREDICTION_TICK, 1);
    }

    private static RvpState stepVelocity(RvpState state, RVP_WeaponData data, double tickTime, double dt) {
        Vec3 velocity = state.velocity();
        Vec3 lookDir = state.lookDir();
        double flightSpeed = Math.max(state.flightSpeed(), velocity.length());
        double secondPulseStartTick = state.secondPulseStartTick();
        if (data.usesPropulsion()) {
            int ignition = data.getResolvedIgnitionDelayTick();
            if (tickTime >= ignition) {
                if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                    lookDir = velocity.normalize();
                }
                double motorTick = tickTime - ignition;
                float burn1 = data.getResolvedMotorBurnTime();
                boolean burning1 = motorTick <= burn1;
                boolean burning2 = false;
                if (!burning1 && data.getProjectileData().usesSecondPulse()) {
                    float speedThreshold = data.getProjectileData().getResolvedSecondPulseTriggerSpeed();
                    if (secondPulseStartTick < 0 && speedThreshold > 0f && velocity.length() <= speedThreshold) {
                        secondPulseStartTick = tickTime;
                    }
                    if (secondPulseStartTick >= 0) {
                        double t2 = tickTime - secondPulseStartTick;
                        burning2 = t2 >= 0 && t2 <= data.getProjectileData().getResolvedSecondPulseBurnTime();
                    }
                }
                if (burning1 || burning2) {
                    float mass = Math.max(data.getResolvedMass(), 1.0E-6f);
                    float thrust = burning1 ? data.getResolvedThrust() : data.getProjectileData().getResolvedSecondPulseThrust();
                    velocity = velocity.add(lookDir.scale((thrust / mass) * dt));
                }
                double speedSqr = velocity.lengthSqr();
                float dragCoeff = data.getResolvedDragCoefficient();
                if (speedSqr > 1.0E-12 && dragCoeff > 0f) {
                    velocity = velocity.add(velocity.normalize().scale(-dragCoeff * speedSqr * dt));
                }
                velocity = applyGravity(velocity, data, dt);
                velocity = clampSpeed(velocity, data);
                if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                    lookDir = velocity.normalize();
                }
            }
            return new RvpState(velocity, lookDir, Math.max(flightSpeed, velocity.length()), secondPulseStartTick);
        }

        velocity = velocity.add(0.0, data.getGravity() * dt, 0.0);
        velocity = applyMchHorizontalDrag(velocity, data.getDragInAir(), dt);
        if (data.getProjectileData().isConstantSpeed() && velocity.lengthSqr() > 1.0E-6) {
            velocity = velocity.normalize().scale(Math.max(flightSpeed, 0.01));
        }
        velocity = clampSpeed(velocity, data);
        if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
            lookDir = velocity.normalize();
        }
        return new RvpState(velocity, lookDir, Math.max(flightSpeed, velocity.length()), secondPulseStartTick);
    }

    private static Vec3 applyGravity(Vec3 velocity, RVP_WeaponData data, double dt) {
        float gravity = data.getGravity();
        if (gravity != 0f) {
            return velocity.add(0.0, gravity * dt, 0.0);
        }
        return velocity.add(0.0, -PhysicsEngine.G * dt, 0.0);
    }

    private static Vec3 applyMchHorizontalDrag(Vec3 velocity, float drag, double dt) {
        if (drag <= 0f) {
            return velocity;
        }
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        double dirX = velocity.x / speed;
        double dirZ = velocity.z / speed;
        return new Vec3(
                velocity.x - dirX * drag * dt,
                velocity.y,
                velocity.z - dirZ * drag * dt
        );
    }

    private static int resolveSubStepCount(double speedPerTick) {
        int bySpeed = (int) Math.ceil(Math.max(speedPerTick, 0.0D) / SUBSTEP_SPEED_SCALE);
        return Math.max(MIN_SUBSTEP, Math.min(MAX_SUBSTEP, bySpeed));
    }

    private static Vec3 clampSpeed(Vec3 velocity, RVP_WeaponData data) {
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        float min = data.getProjectileData().getMinSpeed();
        float max = data.getProjectileData().getMaxSpeed();
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
}
