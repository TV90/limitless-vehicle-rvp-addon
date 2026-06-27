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
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.pojo.Bolt;

import java.util.ArrayList;
import java.util.List;

public final class RVP_RocketBallistics {
    private static final int DEFAULT_RVP_PREDICTION_TICK = 240;

    private RVP_RocketBallistics() {}

    public record Params(double velocity, double gravity, double drag, int predictionTick) {}
    private record RvpState(Vec3 velocity, Vec3 lookDir, double flightSpeed, int secondPulseStartTick) {}

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
        List<AimContext> contexts = weaponUnit.aimContexts();
        if (contexts.isEmpty()) {
            contexts = List.of(weaponUnit.aimContext());
        }
        if (contexts.isEmpty()) {
            return null;
        }
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO) {
            List<Vec3> impacts = new ArrayList<>();
            for (AimContext context : contexts) {
                Vec3 impact = computeAimContextImpact(level, context, vehicleVelocity, data, clipEntity);
                if (impact != null) {
                    impacts.add(impact);
                }
            }
            return averageImpact(impacts);
        }
        int nextIndex = resolveCurrentBoltIndex(weaponUnit, contexts.size());
        return computeAimContextImpact(level, contexts.get(nextIndex), vehicleVelocity, data, clipEntity);
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
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO) {
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
            Vec3 nextPos = pos.add(velocity);
            BlockHitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipEntity));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getLocation();
            }
            pos = nextPos;
            velocity = stepVelocity(velocity, params);
            if (pos.y < level.getMinBuildHeight() - 16) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    public static Vec3 computeImpact(Level level, Vec3 startPos, Vec3 startVelocity, Vec3 startLookDir,
                                     RVP_WeaponData data, @Nullable Entity clipEntity) {
        Vec3 pos = startPos;
        Vec3 lookDir = startLookDir.lengthSqr() > 1.0E-6 ? startLookDir.normalize() : Vec3.ZERO;
        RvpState state = new RvpState(startVelocity, lookDir, Math.max(startVelocity.length(), 0.01), -1);
        for (int tick = 0; tick < resolvePredictionTick(data); tick++) {
            Vec3 nextPos = pos.add(state.velocity());
            BlockHitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipEntity));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getLocation();
            }
            pos = nextPos;
            state = stepVelocity(state, data, tick);
            if (pos.y < level.getMinBuildHeight() - 16) {
                return null;
            }
        }
        return null;
    }

    public static Vec3 stepVelocity(Vec3 velocity, Params params) {
        Vec3 next = velocity.add(0.0, -params.gravity(), 0.0);
        if (params.drag() <= 0.0) {
            return next;
        }
        double speed = next.length();
        if (speed <= 1.0E-6) {
            return next;
        }
        double nextSpeed = Math.max(0.0, speed - params.drag());
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
        Vec2 direction = aimContext.direction;
        Vec3 lookDir = VectorUtil.rotToVec(direction.x, direction.y).normalize();
        Vec3 launchVelocity = lookDir.scale(data.resolveMuzzleSpeed(RVP_EnumWeaponKind.ROCKET));
        if (data.isInheritVehicleVelocity()) {
            launchVelocity = launchVelocity.add(vehicleVelocity);
        }
        return computeImpact(level, RVP_AimContexts.muzzle(aimContext), launchVelocity, lookDir, data, clipEntity);
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
        return Math.max(DEFAULT_RVP_PREDICTION_TICK, 1);
    }

    private static RvpState stepVelocity(RvpState state, RVP_WeaponData data, int tick) {
        Vec3 velocity = state.velocity();
        Vec3 lookDir = state.lookDir();
        double flightSpeed = Math.max(state.flightSpeed(), velocity.length());
        int secondPulseStartTick = state.secondPulseStartTick();
        if (data.usesPropulsion()) {
            int ignition = data.getResolvedIgnitionDelayTick();
            if (tick >= ignition) {
                if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                    lookDir = velocity.normalize();
                }
                int motorTick = tick - ignition;
                float burn1 = data.getResolvedMotorBurnTime();
                boolean burning1 = motorTick <= burn1;
                boolean burning2 = false;
                if (!burning1 && data.getProjectileData().usesSecondPulse()) {
                    float speedThreshold = data.getProjectileData().getResolvedSecondPulseTriggerSpeed();
                    if (secondPulseStartTick < 0 && speedThreshold > 0f && velocity.length() <= speedThreshold) {
                        secondPulseStartTick = tick;
                    }
                    if (secondPulseStartTick >= 0) {
                        int t2 = tick - secondPulseStartTick;
                        burning2 = t2 >= 0 && t2 <= data.getProjectileData().getResolvedSecondPulseBurnTime();
                    }
                }
                if (burning1 || burning2) {
                    float mass = Math.max(data.getResolvedMass(), 1.0E-6f);
                    float thrust = burning1 ? data.getResolvedThrust() : data.getProjectileData().getResolvedSecondPulseThrust();
                    velocity = velocity.add(lookDir.scale(thrust / mass));
                }
                double speedSqr = velocity.lengthSqr();
                float dragCoeff = data.getResolvedDragCoefficient();
                if (speedSqr > 1.0E-12 && dragCoeff > 0f) {
                    velocity = velocity.add(velocity.normalize().scale(-dragCoeff * speedSqr));
                }
                velocity = applyGravity(velocity, data);
                velocity = clampSpeed(velocity, data);
                if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
                    lookDir = velocity.normalize();
                }
            }
            return new RvpState(velocity, lookDir, Math.max(flightSpeed, velocity.length()), secondPulseStartTick);
        }

        velocity = velocity.add(0.0, data.getGravity(), 0.0);
        velocity = applyMchHorizontalDrag(velocity, data.getDragInAir());
        if (data.getProjectileData().isConstantSpeed() && velocity.lengthSqr() > 1.0E-6) {
            velocity = velocity.normalize().scale(Math.max(flightSpeed, 0.01));
        }
        velocity = clampSpeed(velocity, data);
        if (data.getProjectileData().isRotateToMotion() && velocity.lengthSqr() > 1.0E-6) {
            lookDir = velocity.normalize();
        }
        return new RvpState(velocity, lookDir, Math.max(flightSpeed, velocity.length()), secondPulseStartTick);
    }

    private static Vec3 applyGravity(Vec3 velocity, RVP_WeaponData data) {
        float gravity = data.getGravity();
        if (gravity != 0f) {
            return velocity.add(0.0, gravity, 0.0);
        }
        return velocity.add(0.0, -0.0245, 0.0);
    }

    private static Vec3 applyMchHorizontalDrag(Vec3 velocity, float drag) {
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
                velocity.x - dirX * drag,
                velocity.y,
                velocity.z - dirZ * drag
        );
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
