package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.client.map.RVP_TacticalMapCache;
import org.ywzj.rvp.util.RVP_CcipUtil;
import org.ywzj.rvp.weapon.RVP_RocketBallistics;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-side inverse CCIP solver and automatic artillery laying state. */
public final class RVP_ArtilleryFireControlState {

    public enum TrajectoryMode {
        HIGH,
        LOW
    }

    private static final double COARSE_ELEVATION_STEP_DEG = 1.0D;
    private static final int ROOT_REFINE_ITERATIONS = 12;
    private static final double HIGH_ANGLE_THRESHOLD_DEG = 45.0D;
    private static final int RESOLVE_INTERVAL_TICK = 8;
    private static final int HOVER_RESOLVE_INTERVAL_TICK = 2;

    @Nullable
    private static Vec3 designatedTarget;
    @Nullable
    private static Solution solution;
    private static int designatedVehicleId = Integer.MIN_VALUE;
    @Nullable
    private static ResourceLocation designatedWeaponId;
    private static int lastResolveTick = Integer.MIN_VALUE;
    @Nullable
    private static Vec3 lastSolveVehiclePos;
    @Nullable
    private static Vec3 lastSolveVehicleVelocity;
    private static TrajectoryMode trajectoryMode = TrajectoryMode.HIGH;

    private RVP_ArtilleryFireControlState() {}

    public record Snapshot(@Nullable Vec3 target, boolean hasSolution, boolean exact,
                           double elevationDeg, double missDistance) {}

    private record Context(AbstractVehicle vehicle, WeaponUnit operatorUnit, WeaponUnit launchUnit,
                           RVP_WeaponBase weapon, RVP_WeaponData data, RVP_EnumWeaponKind kind,
                           ResourceLocation weaponId) {}

    record Sample(double elevationDeg, Vec3 direction, @Nullable Vec3 impact,
                  double rangeResidual, double missDistance) {}

    private record Solution(Vec3 aimPoint, @Nullable Vec3 predictedImpact, double elevationDeg,
                            double missDistance, boolean exact, int vehicleId,
                            ResourceLocation weaponId) {}

    public static void designate(Vec3 target) {
        if (target == null) {
            return;
        }
        if (designatedTarget != null && designatedTarget.distanceToSqr(target) <= 0.01D) {
            return;
        }
        designatedTarget = target;
        solution = null;
    }

    public static void clear() {
        designatedTarget = null;
        solution = null;
        designatedVehicleId = Integer.MIN_VALUE;
        designatedWeaponId = null;
        lastResolveTick = Integer.MIN_VALUE;
        lastSolveVehiclePos = null;
        lastSolveVehicleVelocity = null;
    }

    @Nullable
    public static Vec3 getDesignatedTarget() {
        return designatedTarget;
    }

    public static Snapshot snapshot() {
        Solution current = solution;
        return current == null
                ? new Snapshot(designatedTarget, false, false, 0.0D, Double.POSITIVE_INFINITY)
                : new Snapshot(designatedTarget, true, current.exact(), current.elevationDeg(), current.missDistance());
    }

    public static TrajectoryMode getTrajectoryMode() {
        return trajectoryMode;
    }

    public static void toggleTrajectoryMode() {
        trajectoryMode = trajectoryMode == TrajectoryMode.HIGH
                ? TrajectoryMode.LOW
                : TrajectoryMode.HIGH;
        solution = null;
        lastResolveTick = Integer.MIN_VALUE;
    }

    public static void tick(Minecraft mc) {
        if (!(mc.screen instanceof RVP_TacticalMapScreen screen) || !screen.isArtilleryMode()) {
            return;
        }
        if (designatedTarget == null || LocalVehiclePlayer.instance == null) {
            return;
        }
        Context context = resolveContext(LocalVehiclePlayer.instance);
        if (context == null) {
            solution = null;
            return;
        }
        boolean contextChanged = context.vehicle().getId() != designatedVehicleId
                || !context.weaponId().equals(designatedWeaponId);
        boolean sourceMoved = lastSolveVehiclePos == null
                || lastSolveVehiclePos.distanceToSqr(context.vehicle().position()) > 0.0625D
                || lastSolveVehicleVelocity == null
                || lastSolveVehicleVelocity.distanceToSqr(context.vehicle().getDeltaMovement()) > 0.0025D;
        if (!contextChanged && solution == null && lastResolveTick != Integer.MIN_VALUE
                && context.vehicle().tickCount - lastResolveTick < HOVER_RESOLVE_INTERVAL_TICK) {
            return;
        }
        if (!contextChanged && solution != null && !sourceMoved) {
            return;
        }
        if (!contextChanged && solution != null && sourceMoved
                && context.vehicle().tickCount - lastResolveTick < RESOLVE_INTERVAL_TICK) {
            return;
        }
        solution = solve(context, designatedTarget);
        designatedVehicleId = context.vehicle().getId();
        designatedWeaponId = context.weaponId();
        lastResolveTick = context.vehicle().tickCount;
        lastSolveVehiclePos = context.vehicle().position();
        lastSolveVehicleVelocity = context.vehicle().getDeltaMovement();
    }

    public static boolean applyAutomaticAim(LocalVehiclePlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof RVP_TacticalMapScreen screen) || !screen.isArtilleryMode()) {
            return false;
        }
        Solution current = solution;
        if (current == null) {
            return false;
        }
        Context context = resolveContext(player);
        if (context == null
                || context.vehicle().getId() != current.vehicleId()
                || !context.weaponId().equals(current.weaponId())) {
            return false;
        }
        context.operatorUnit().aim(current.aimPoint());
        return true;
    }

    @Nullable
    private static Context resolveContext(LocalVehiclePlayer player) {
        if (player == null || !player.onVehicle()) {
            return null;
        }
        WeaponUnit operatorUnit = player.getWeaponUnit();
        if (operatorUnit == null) {
            return null;
        }
        AbstractVehicleWeapon<?> currentWeapon = operatorUnit.getCurrentWeapon().orElse(null);
        if (!(currentWeapon instanceof RVP_WeaponBase weapon)) {
            return null;
        }
        RVP_WeaponData data = weapon.getData();
        RVP_EnumWeaponKind kind = data.getWeaponKind();
        if (!data.getMiscData().isArtilleryMap()
                || (kind != RVP_EnumWeaponKind.ROCKET && kind != RVP_EnumWeaponKind.BOMB)) {
            return null;
        }
        WeaponUnit launchUnit = currentWeapon.getWeaponUnit();
        ResourceLocation weaponId = data.getWeaponId();
        if (launchUnit == null || weaponId == null) {
            return null;
        }
        return new Context(player.getVehicle(), operatorUnit, launchUnit, weapon, data, kind, weaponId);
    }

    private static Solution solve(Context context, Vec3 target) {
        Vec3 muzzle = RVP_AimContexts.muzzle(context.launchUnit().aimContext());
        Vec3 horizontalTarget = new Vec3(target.x - muzzle.x, 0.0D, target.z - muzzle.z);
        double targetDistance = horizontalTarget.length();
        if (targetDistance <= 1.0E-6D) {
            Vec3 aimPoint = muzzle.add(0.0D, 2048.0D, 0.0D);
            return new Solution(aimPoint, null, 90.0D, targetDistance, false,
                    context.vehicle().getId(), context.weaponId());
        }

        Vec3 targetDirection = horizontalTarget.scale(1.0D / targetDistance);
        Vec2 rootAimRot = context.operatorUnit().aimRot(target);
        float yawMin = context.operatorUnit().getYRotMin() - context.operatorUnit().ySelfRot;
        float yawMax = context.operatorUnit().getYRotMax() - context.operatorUnit().ySelfRot;
        float desiredLocalYaw = Mth.wrapDegrees(rootAimRot.y);
        float reachableLocalYaw = Mth.clamp(desiredLocalYaw, yawMin, yawMax);
        boolean yawReachable = Math.abs(Mth.wrapDegrees(desiredLocalYaw - reachableLocalYaw)) <= 0.5F;
        Vec3 reachableWorldDirection = context.operatorUnit().worldVec(0.0F, reachableLocalYaw);
        Vec3 reachableHorizontal = new Vec3(reachableWorldDirection.x, 0.0D, reachableWorldDirection.z);
        if (reachableHorizontal.lengthSqr() <= 1.0E-8D) {
            reachableHorizontal = targetDirection;
        } else {
            reachableHorizontal = reachableHorizontal.normalize();
        }
        float worldYaw = VectorUtil.vecToRot(reachableHorizontal).y;

        double localPitchMin = context.launchUnit().getXRotMin() - context.launchUnit().xSelfRot;
        double localPitchMax = context.launchUnit().getXRotMax() - context.launchUnit().xSelfRot;
        double minElevation = Mth.clamp(Math.min(-localPitchMax, -localPitchMin), -89.0D, 89.0D);
        double maxElevation = Mth.clamp(Math.max(-localPitchMax, -localPitchMin), -89.0D, 89.0D);

        List<Sample> candidates = new ArrayList<>();
        Sample previous = null;
        for (double elevation = minElevation; elevation <= maxElevation + 1.0E-6D;
             elevation += COARSE_ELEVATION_STEP_DEG) {
            Sample sample = evaluate(context, target, muzzle, targetDirection, targetDistance, worldYaw, elevation);
            if (sample == null) {
                previous = null;
                continue;
            }
            candidates.add(sample);
            if (previous != null && signsDiffer(previous.rangeResidual(), sample.rangeResidual())) {
                Sample root = refineRoot(context, target, muzzle, targetDirection, targetDistance,
                        worldYaw, previous, sample);
                if (root != null) {
                    candidates.add(root);
                }
            }
            previous = sample;
        }

        Sample selected = selectPreferred(candidates, targetDistance, yawReachable, trajectoryMode);
        if (selected == null) {
            double directElevation = Math.toDegrees(Math.atan2(target.y - muzzle.y, targetDistance));
            double clampedElevation = Mth.clamp(directElevation, minElevation, maxElevation);
            Vec3 direction = VectorUtil.rotToVec((float) -clampedElevation, worldYaw).normalize();
            return new Solution(muzzle.add(direction.scale(2048.0D)), null, clampedElevation,
                    Double.POSITIVE_INFINITY, false, context.vehicle().getId(), context.weaponId());
        }
        double tolerance = solutionTolerance(targetDistance);
        boolean exact = yawReachable && selected.missDistance() <= tolerance;
        return new Solution(muzzle.add(selected.direction().scale(2048.0D)), selected.impact(),
                selected.elevationDeg(), selected.missDistance(), exact,
                context.vehicle().getId(), context.weaponId());
    }

    @Nullable
    private static Sample refineRoot(Context context, Vec3 target, Vec3 muzzle, Vec3 targetDirection,
                                     double targetDistance, float worldYaw, Sample low, Sample high) {
        Sample left = low;
        Sample right = high;
        Sample best = left.missDistance() <= right.missDistance() ? left : right;
        for (int i = 0; i < ROOT_REFINE_ITERATIONS; i++) {
            double elevation = (left.elevationDeg() + right.elevationDeg()) * 0.5D;
            Sample middle = evaluate(context, target, muzzle, targetDirection, targetDistance, worldYaw, elevation);
            if (middle == null) {
                break;
            }
            if (middle.missDistance() < best.missDistance()) {
                best = middle;
            }
            if (signsDiffer(left.rangeResidual(), middle.rangeResidual())) {
                right = middle;
            } else {
                left = middle;
            }
        }
        return best;
    }

    @Nullable
    private static Sample evaluate(Context context, Vec3 target, Vec3 muzzle, Vec3 targetDirection,
                                   double targetDistance, float worldYaw, double elevationDeg) {
        Vec3 direction = VectorUtil.rotToVec((float) -elevationDeg, worldYaw).normalize();
        Vec3 velocity = direction.scale(context.data().resolveMuzzleSpeed(context.kind()));
        if (context.data().isInheritVehicleVelocity()) {
            velocity = velocity.add(context.vehicle().getDeltaMovement());
        }
        Vec3 impact = context.kind() == RVP_EnumWeaponKind.BOMB
                ? RVP_CcipUtil.computeBombImpact(context.vehicle().level(), muzzle, velocity, context.data())
                : RVP_RocketBallistics.computeArtilleryImpact(context.vehicle().level(), muzzle, velocity,
                        direction, context.data(), context.vehicle(),
                        RVP_RocketBallistics.ARTILLERY_PREDICTION_TICK, target.y,
                        RVP_TacticalMapCache::getCachedHeight);
        if (impact == null) {
            return null;
        }
        Vec3 delta = new Vec3(impact.x - muzzle.x, 0.0D, impact.z - muzzle.z);
        double alongRange = delta.dot(targetDirection);
        double dx = impact.x - target.x;
        double dz = impact.z - target.z;
        double missDistance = Math.sqrt(dx * dx + dz * dz);
        return new Sample(elevationDeg, direction, impact, alongRange - targetDistance, missDistance);
    }

    @Nullable
    static Sample selectPreferred(List<Sample> candidates, double targetDistance, boolean yawReachable) {
        return selectPreferred(candidates, targetDistance, yawReachable, TrajectoryMode.HIGH);
    }

    @Nullable
    static Sample selectPreferred(List<Sample> candidates, double targetDistance, boolean yawReachable,
                                  TrajectoryMode mode) {
        if (candidates.isEmpty()) {
            return null;
        }
        double tolerance = solutionTolerance(targetDistance);
        Comparator<Sample> byMissThenHigher = Comparator
                .comparingDouble(Sample::missDistance)
                .thenComparing(Comparator.comparingDouble(Sample::elevationDeg).reversed());
        Comparator<Sample> byMissThenLower = Comparator
                .comparingDouble(Sample::missDistance)
                .thenComparingDouble(Sample::elevationDeg);
        if (yawReachable) {
            Sample requestedExact = candidates.stream()
                    .filter(sample -> mode == TrajectoryMode.HIGH
                            ? sample.elevationDeg() >= HIGH_ANGLE_THRESHOLD_DEG
                            : sample.elevationDeg() <= HIGH_ANGLE_THRESHOLD_DEG)
                    .filter(sample -> sample.missDistance() <= tolerance)
                    .min(mode == TrajectoryMode.HIGH ? byMissThenHigher : byMissThenLower)
                    .orElse(null);
            if (requestedExact != null) {
                return requestedExact;
            }
            Sample anyExact = candidates.stream()
                    .filter(sample -> sample.missDistance() <= tolerance)
                    .min(mode == TrajectoryMode.HIGH ? byMissThenHigher : byMissThenLower)
                    .orElse(null);
            if (anyExact != null) {
                return anyExact;
            }
        }
        boolean allShort = candidates.stream().allMatch(sample -> sample.rangeResidual() < 0.0D);
        if (allShort) {
            return candidates.stream()
                    .max(Comparator.comparingDouble(Sample::rangeResidual)
                            .thenComparingDouble(Sample::elevationDeg))
                    .orElse(null);
        }
        boolean allLong = candidates.stream().allMatch(sample -> sample.rangeResidual() > 0.0D);
        if (allLong) {
            return candidates.stream()
                    .min(Comparator.comparingDouble(Sample::rangeResidual)
                            .thenComparing(mode == TrajectoryMode.HIGH
                                    ? Comparator.comparingDouble(Sample::elevationDeg).reversed()
                                    : Comparator.comparingDouble(Sample::elevationDeg)))
                    .orElse(null);
        }
        return candidates.stream().min(byMissThenHigher).orElse(null);
    }

    private static boolean signsDiffer(double a, double b) {
        return a == 0.0D || b == 0.0D || Math.signum(a) != Math.signum(b);
    }

    private static double solutionTolerance(double targetDistance) {
        return Mth.clamp(targetDistance * 0.005D, 2.5D, 8.0D);
    }
}
