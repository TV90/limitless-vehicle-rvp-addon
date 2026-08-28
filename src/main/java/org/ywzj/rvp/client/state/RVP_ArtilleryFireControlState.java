package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
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
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    /** 偏航轴进入该误差范围后，才允许俯仰轴追踪最新弹道解。 */
    static final float YAW_REACHED_TOLERANCE_DEG = 0.5F;

    @Nullable
    private static Vec3 designatedTarget;
    @Nullable
    private static Solution solution;
    /** 地图目标或弹道模式每次变化时递增，用于识别解算结果是否仍对应最新输入。 */
    private static long targetRevision;
    private static int designatedVehicleId = Integer.MIN_VALUE;
    @Nullable
    private static ResourceLocation designatedWeaponId;
    private static int lastResolveTick = Integer.MIN_VALUE;
    @Nullable
    private static Vec3 lastSolveVehiclePos;
    @Nullable
    private static Vec3 lastSolveVehicleVelocity;
    private static TrajectoryMode trajectoryMode = TrajectoryMode.HIGH;
    /** 控制“先偏航、后俯仰”的纯状态门，独立于具体武器站，便于单元测试。 */
    private static final PitchGate PITCH_GATE = new PitchGate();
    /** 进入偏航等待阶段时，各武器站捕获的当前实际俯仰角。 */
    private static final Map<WeaponUnit, Float> LOCKED_PITCHES = new IdentityHashMap<>();
    /** 当前门控所属载具，防止换车后复用旧俯仰锁。 */
    private static int aimVehicleId = Integer.MIN_VALUE;
    /** 当前门控所属武器，防止切换弹种后复用旧俯仰锁。 */
    @Nullable
    private static ResourceLocation aimWeaponId;
    /** 当前门控所属操作武器站实例。 */
    @Nullable
    private static WeaponUnit aimOperatorUnit;
    /** 当前门控所属实际发射武器站实例。 */
    @Nullable
    private static WeaponUnit aimLaunchUnit;

    private RVP_ArtilleryFireControlState() {}

    public record Snapshot(@Nullable Vec3 target, boolean hasSolution, boolean exact,
                           double elevationDeg, double missDistance) {}

    private record Context(AbstractVehicle vehicle, WeaponUnit operatorUnit, WeaponUnit launchUnit,
                           RVP_WeaponBase weapon, RVP_WeaponData data, RVP_EnumWeaponKind kind,
                           ResourceLocation weaponId) {}

    record Sample(double elevationDeg, Vec3 direction, @Nullable Vec3 impact,
                  double rangeResidual, double missDistance) {}

    /**
     * 一次炮兵逆解结果。
     *
     * @param aimPoint 指向解算弹道的世界坐标瞄准点
     * @param predictedImpact 预测落点；未形成有效落点时为 {@code null}
     * @param elevationDeg 解算俯仰角，单位为度
     * @param missDistance 预测落点与地图目标的水平误差，单位为格
     * @param exact 是否已达到命中误差阈值
     * @param vehicleId 解算时使用的载具实体 ID
     * @param weaponId 解算时使用的武器 ID
     * @param targetRevision 解算对应的地图目标/弹道模式版本
     */
    private record Solution(Vec3 aimPoint, @Nullable Vec3 predictedImpact, double elevationDeg,
                            double missDistance, boolean exact, int vehicleId,
                            ResourceLocation weaponId, long targetRevision) {}

    /**
     * 一次俯仰门控判定的结果。
     *
     * @param lockPitch 当前帧是否保持已捕获的实际俯仰角
     * @param capturePitch 当前帧是否需要首次捕获各武器站的实际俯仰角
     */
    record GateDecision(boolean lockPitch, boolean capturePitch) {}

    /**
     * 只负责决定俯仰是否应锁定，不持有 Minecraft 或本体对象，确保边界行为可直接单测。
     */
    static final class PitchGate {
        /** 上一次处理的地图目标版本。 */
        private long handledRevision = Long.MIN_VALUE;
        /** 当前是否正等待最新解算和偏航到位。 */
        private boolean locked;

        GateDecision update(long revision, boolean movableYaw, boolean latestSolution,
                            float currentYaw, float targetYaw) {
            if (!movableYaw) {
                handledRevision = revision;
                locked = false;
                return new GateDecision(false, false);
            }
            boolean revisionChanged = revision != handledRevision;
            handledRevision = revision;
            boolean yawReached = isYawReached(currentYaw, targetYaw);
            boolean capturePitch = revisionChanged && !locked && (!latestSolution || !yawReached);
            if (revisionChanged && (!latestSolution || !yawReached)) {
                locked = true;
            }
            if (locked && latestSolution && yawReached) {
                locked = false;
            }
            return new GateDecision(locked, capturePitch);
        }

        void reset() {
            handledRevision = Long.MIN_VALUE;
            locked = false;
        }
    }

    public static void designate(Vec3 target) {
        if (target == null) {
            return;
        }
        if (designatedTarget != null && designatedTarget.distanceToSqr(target) <= 0.01D) {
            return;
        }
        designatedTarget = target;
        targetRevision++;
    }

    public static void clear() {
        designatedTarget = null;
        solution = null;
        designatedVehicleId = Integer.MIN_VALUE;
        designatedWeaponId = null;
        lastResolveTick = Integer.MIN_VALUE;
        lastSolveVehiclePos = null;
        lastSolveVehicleVelocity = null;
        targetRevision = 0L;
        resetAimGate();
    }

    @Nullable
    public static Vec3 getDesignatedTarget() {
        return designatedTarget;
    }

    public static Snapshot snapshot() {
        Solution current = solution;
        return current == null || current.targetRevision() != targetRevision
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
        targetRevision++;
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
            // 无有效上下文时重置缓存，防止切回炮兵载具时 contextChanged 误判为 false
            solution = null;
            designatedVehicleId = Integer.MIN_VALUE;
            designatedWeaponId = null;
            lastResolveTick = Integer.MIN_VALUE;
            lastSolveVehiclePos = null;
            lastSolveVehicleVelocity = null;
            resetAimGate();
            return;
        }
        boolean contextChanged = context.vehicle().getId() != designatedVehicleId
                || !context.weaponId().equals(designatedWeaponId);
        boolean sourceMoved = lastSolveVehiclePos == null
                || lastSolveVehiclePos.distanceToSqr(context.vehicle().position()) > 0.0625D
                || lastSolveVehicleVelocity == null
                || lastSolveVehicleVelocity.distanceToSqr(context.vehicle().getDeltaMovement()) > 0.0025D;
        // "无解"的 Solution（missDistance == POSITIVE_INFINITY）不应被节流逻辑跳过，
        // 否则从无人机切回火箭炮时第一次解算失败后会卡在"无解"状态。
        boolean latestSolution = solution != null && solution.targetRevision() == targetRevision;
        boolean hasValidSolution = latestSolution
                && solution.missDistance() < Double.POSITIVE_INFINITY;
        // 有解但不精确（如炮口状态未同步导致 yaw 暂不可达、exact=false）同样需要重试：
        // 专用服务器下网络同步慢，第一次解算常得到非精确解，若当作有效解节流会永久"无解"，
        // 必须切换弹道才强制重解；此处按慢节奏持续重解，待同步完成后自然收敛为精确解。
        boolean hasExactSolution = latestSolution && solution.exact();
        if (!contextChanged && lastResolveTick != Integer.MIN_VALUE) {
            int sinceResolve = context.vehicle().tickCount - lastResolveTick;
            if (!hasValidSolution) {
                if (sinceResolve < HOVER_RESOLVE_INTERVAL_TICK) {
                    return;
                }
            } else if (!hasExactSolution) {
                if (sinceResolve < RESOLVE_INTERVAL_TICK) {
                    return;
                }
            } else if (!sourceMoved) {
                return;
            } else if (sinceResolve < RESOLVE_INTERVAL_TICK) {
                return;
            }
        }
        solution = solve(context, designatedTarget, targetRevision);
        designatedVehicleId = context.vehicle().getId();
        designatedWeaponId = context.weaponId();
        lastResolveTick = context.vehicle().tickCount;
        lastSolveVehiclePos = context.vehicle().position();
        lastSolveVehicleVelocity = context.vehicle().getDeltaMovement();
    }

    public static boolean applyAutomaticAim(LocalVehiclePlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof RVP_TacticalMapScreen screen) || !screen.isArtilleryMode()) {
            resetAimGate();
            return false;
        }
        Vec3 target = designatedTarget;
        if (target == null) {
            resetAimGate();
            return false;
        }
        Context context = resolveContext(player);
        if (context == null) {
            resetAimGate();
            return false;
        }
        if (aimContextChanged(context)) {
            resetAimGate();
            aimVehicleId = context.vehicle().getId();
            aimWeaponId = context.weaponId();
            aimOperatorUnit = context.operatorUnit();
            aimLaunchUnit = context.launchUnit();
        }

        Solution current = solution;
        boolean latestSolution = current != null
                && current.targetRevision() == targetRevision
                && context.vehicle().getId() == current.vehicleId()
                && context.weaponId().equals(current.weaponId());
        Vec3 yawAimPoint = latestSolution ? current.aimPoint() : target;
        float targetYaw = reachableYaw(context.operatorUnit(), yawAimPoint);
        boolean movableYaw = context.operatorUnit().rotByAim
                && context.operatorUnit().getYRotSpeed() > 0.0F
                && context.operatorUnit().getYRotMax() - context.operatorUnit().getYRotMin() > 1.0E-4F;
        GateDecision decision = PITCH_GATE.update(targetRevision, movableYaw, latestSolution,
                context.operatorUnit().getYRot(), targetYaw);

        if (!movableYaw) {
            LOCKED_PITCHES.clear();
            if (!latestSolution) {
                // 固定挂架没有可等待的偏航轴；解算未完成时保留本体原有视线瞄准行为。
                return false;
            }
            // 调用本体递归瞄准，将最新完整解算角同步到操作武器站及其子武器站。
            context.operatorUnit().aim(current.aimPoint());
            return true;
        }

        if (decision.capturePitch()) {
            captureCurrentPitches(context.operatorUnit());
        }
        if (decision.lockPitch()) {
            applyLockedPitchAim(context.operatorUnit(), yawAimPoint);
            return true;
        }
        LOCKED_PITCHES.clear();
        if (!latestSolution) {
            return true;
        }
        // 调用本体递归瞄准，在偏航到位后才释放各子武器站追踪最新俯仰解。
        context.operatorUnit().aim(current.aimPoint());
        return true;
    }

    private static boolean aimContextChanged(Context context) {
        return aimVehicleId != context.vehicle().getId()
                || !context.weaponId().equals(aimWeaponId)
                || aimOperatorUnit != context.operatorUnit()
                || aimLaunchUnit != context.launchUnit();
    }

    private static void resetAimGate() {
        PITCH_GATE.reset();
        LOCKED_PITCHES.clear();
        aimVehicleId = Integer.MIN_VALUE;
        aimWeaponId = null;
        aimOperatorUnit = null;
        aimLaunchUnit = null;
    }

    private static void captureCurrentPitches(WeaponUnit root) {
        forEachAimUnit(root, unit -> LOCKED_PITCHES.put(unit, unit.getXRot()));
    }

    private static void applyLockedPitchAim(WeaponUnit root, Vec3 yawAimPoint) {
        forEachAimUnit(root, unit -> {
            Float lockedPitch = LOCKED_PITCHES.get(unit);
            if (lockedPitch == null || !unit.rotByAim) {
                return;
            }
            float targetYaw = reachableYaw(unit, yawAimPoint);
            applyUnitAim(unit, lockedPitch, targetYaw);
        });
    }

    private static void forEachAimUnit(WeaponUnit root, java.util.function.Consumer<WeaponUnit> action) {
        Map<WeaponUnit, Boolean> visited = new IdentityHashMap<>();
        forEachAimUnit(root, action, visited);
    }

    private static void forEachAimUnit(WeaponUnit unit, java.util.function.Consumer<WeaponUnit> action,
                                       Map<WeaponUnit, Boolean> visited) {
        if (unit == null || visited.put(unit, Boolean.TRUE) != null) {
            return;
        }
        action.accept(unit);
        // 调用本体公开的子武器站关系，覆盖与 WeaponUnit.aim 相同的递归瞄准范围。
        for (WeaponUnit child : unit.getSubWeaponUnits()) {
            forEachAimUnit(child, action, visited);
        }
    }

    private static float reachableYaw(WeaponUnit unit, Vec3 worldAimPoint) {
        // 调用本体 aimRot，将世界地图目标转换为当前武器站的局部偏航角。
        float desiredYaw = Mth.wrapDegrees(unit.aimRot(worldAimPoint).y - unit.yBarrelSelfRot);
        float yawMin = unit.getYRotMin() - unit.ySelfRot;
        float yawMax = unit.getYRotMax() - unit.ySelfRot;
        return Mth.clamp(desiredYaw, yawMin, yawMax);
    }

    private static void applyUnitAim(WeaponUnit unit, float pitch, float yaw) {
        if (Float.compare(unit.getXAimRot(), pitch) == 0 && Float.compare(unit.getYAimRot(), yaw) == 0) {
            return;
        }
        // 调用本体旋转 setter，只改瞄准目标角，实际部件仍按 JSON 配置速度平滑转动。
        unit.setXAimRot(pitch);
        unit.setYAimRot(yaw);
        // 调用本体更新世界瞄准向量，保持火控、射击方向和客户端显示使用同一目标角。
        unit.updateWorldAimVec();

        ClientVehicleAction control = new ClientVehicleAction();
        control.vehicleEntityId = unit.getVehicle().getId();
        control.partUnitIndex = unit.getIndex();
        control.xAimRot = pitch;
        control.yAimRot = yaw;
        // 复用本体既有武器站控制包，把偏航优先阶段的分轴目标同步到服务端。
        Channel.CHANNEL.sendToServer(control);
    }

    static boolean isYawReached(float currentYaw, float targetYaw) {
        float delta = (targetYaw - currentYaw) % 360.0F;
        if (delta >= 180.0F) {
            delta -= 360.0F;
        } else if (delta < -180.0F) {
            delta += 360.0F;
        }
        return Math.abs(delta) <= YAW_REACHED_TOLERANCE_DEG;
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
        return new Context(player.vehicle, operatorUnit, launchUnit, weapon, data, kind, weaponId);
    }

    private static Solution solve(Context context, Vec3 target, long revision) {
        Vec3 muzzle = RVP_AimContexts.muzzle(context.launchUnit().aimContext());
        Vec3 horizontalTarget = new Vec3(target.x - muzzle.x, 0.0D, target.z - muzzle.z);
        double targetDistance = horizontalTarget.length();
        if (targetDistance <= 1.0E-6D) {
            Vec3 aimPoint = muzzle.add(0.0D, 2048.0D, 0.0D);
            return new Solution(aimPoint, null, 90.0D, targetDistance, false,
                    context.vehicle().getId(), context.weaponId(), revision);
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
                    Double.POSITIVE_INFINITY, false, context.vehicle().getId(), context.weaponId(), revision);
        }
        double tolerance = solutionTolerance(targetDistance);
        boolean exact = yawReachable && selected.missDistance() <= tolerance;
        return new Solution(muzzle.add(selected.direction().scale(2048.0D)), selected.impact(),
                selected.elevationDeg(), selected.missDistance(), exact,
                context.vehicle().getId(), context.weaponId(), revision);
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
                        (x, z) -> {
                            Integer cached = RVP_TacticalMapCache.getCachedHeight(x, z);
                            if (cached != null) {
                                return cached;
                            }
                            // 后备：缓存缺失时（如从视距外无人机切回）从已加载chunk实时获取
                            return context.vehicle().level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        });
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
