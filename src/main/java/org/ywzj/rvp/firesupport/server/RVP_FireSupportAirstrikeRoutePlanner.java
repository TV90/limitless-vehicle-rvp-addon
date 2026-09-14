package org.ywzj.rvp.firesupport.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;

/**
 * 空中支援公共任务航线的纯数学规划器；不读取世界、不生成实体、不持有服务端状态。
 *
 * <p>GPS 与普通弹道策略先分别提供每发释放点，本实现再统一反推飞机中心、规划入场点和
 * 出场点，并以向心 Catmull-Rom 曲线连接转向段；GPS 对准段及出场段使用直线。
 * 原始投放 Tick 作为每个参考点的不得提前下限；实际飞机可以因固定翼转向限制偏离参考
 * 曲线，投送器会使用实时挂架位置重新解算弹道。</p>
 */
public final class RVP_FireSupportAirstrikeRoutePlanner {
    /** 每个控制点区间的数值采样数；足以让航点切线和弧长在服务端保持稳定。 */
    private static final int SAMPLES_PER_SEGMENT = 32;
    /** 向心 Catmull-Rom 的参数化指数。 */
    private static final double CENTRIPETAL_ALPHA = 0.5D;
    /** 数学比较的最小距离。 */
    private static final double EPSILON = 1.0E-8D;

    private RVP_FireSupportAirstrikeRoutePlanner() {}

    /** 一发弹的原始投放参考；释放坐标已经由类型化空投弹道解算器冻结。 */
    public record ReleaseTarget(
            /** 任务内全局轮次。 */ int roundIndex,
            /** 原始计划投放 Tick；实际投送不得早于此值。 */ long plannedTick,
            /** 原始弹道解算得到的空投释放位置。 */ Vec3 releasePosition,
            /** 原始入场方向和载机速度向量。 */ Vec3 inboundMotion,
            /** GPS 单发策略要求的释放点入场方向；普通弹道策略为 null。 */ @Nullable Vec3 preferredInboundDirection) {
        /** 保留无 GPS 对准标记的普通弹道航点构造形式。 */
        public ReleaseTarget(int roundIndex, long plannedTick, Vec3 releasePosition, Vec3 inboundMotion) {
            this(roundIndex, plannedTick, releasePosition, inboundMotion, null);
        }
    }

    /** 一发弹对应的平滑航线参考点。 */
    public record ScheduledRelease(
            /** 任务内全局轮次。 */ int roundIndex,
            /** 原始计划投放 Tick。 */ long plannedTick,
            /** 参考曲线经过该点时的实际 Tick；不得早于原始 Tick。 */ long actualTick,
            /** 原始空投释放点。 */ Vec3 releasePosition,
            /** 由挂架偏移反推的参考飞机中心。 */ Vec3 aircraftCenter,
            /** 参考曲线上的弧长位置。 */ double distanceAlongRoute,
            /** GPS 单发策略要求的释放点入场方向；普通弹道策略为 null。 */ @Nullable Vec3 preferredInboundDirection) {}

    /** 固定翼参考航线；所有方法只执行确定性数学计算。 */
    public record RoutePlan(
            /** 入场方向单位向量。 */ Vec3 direction,
            /** 初始 Minecraft 飞机偏航角。 */ float aircraftYaw,
            /** 实际可生成飞机的起始 Tick。 */ long startTick,
            /** 参考航线抵达出场点的 Tick；最后一段按出场直线距离计算。 */ long endTick,
            /** 入场、投放参考点和出场点。 */ List<Waypoint> waypoints,
            /** 按原始轮次排列的投放参考。 */ List<ScheduledRelease> releases,
            /** 按弧长采样的平滑曲线。 */ List<CurveSample> curve) {
        public RoutePlan {
            waypoints = List.copyOf(waypoints);
            releases = List.copyOf(releases);
            curve = List.copyOf(curve);
        }

        /** 返回指定 Tick 的参考飞机中心位置；超出路线时保持末端切线继续飞行。 */
        public Vec3 positionAt(long tick) {
            return positionAtDistance(distanceAt(tick));
        }

        /** 返回指定 Tick 的参考运动向量；速度不会超过规划速度上限。 */
        public Vec3 motionAt(long tick) {
            return positionAt(tick + 1L).subtract(positionAt(tick));
        }

        /** 返回指定 Tick 的参考切线。 */
        public Vec3 tangentAt(long tick) {
            return tangentAtDistance(distanceAt(tick));
        }

        /** 返回指定 Tick 的参考挂架位置；实时投送使用控制器姿态变换。 */
        public Vec3 rackAt(long tick, RVP_FireSupportDeliveryTypes.LocalOffset rackOffset) {
            return positionAt(tick).add(rotateRack(rackOffset, direction));
        }

        /** 返回指定轮次的参考投送 Tick；未找到时返回调用方回退值。 */
        public long releaseTick(int roundIndex, long fallback) {
            for (ScheduledRelease release : releases) {
                if (release.roundIndex() == roundIndex) return release.actualTick();
            }
            return fallback;
        }

        /** 返回指定参考投送点在曲线中的索引。 */
        public int releaseIndex(int roundIndex) {
            for (int index = 0; index < releases.size(); index++) {
                if (releases.get(index).roundIndex() == roundIndex) return index;
            }
            return -1;
        }

        /** 返回参考路线的出场点；该点由最后一个参考飞机中心和出场距离确定。 */
        public Vec3 exitPosition() {
            return waypoints.isEmpty() ? Vec3.ZERO : waypoints.get(waypoints.size() - 1).position();
        }

        /** 按弧长取曲线位置。 */
        public Vec3 positionAtDistance(double distance) {
            if (curve.isEmpty()) return Vec3.ZERO;
            if (!Double.isFinite(distance)) return curve.get(0).position();
            if (distance <= 0.0D) return curve.get(0).position();
            if (distance >= totalDistance()) return curve.get(curve.size() - 1).position();
            int high = upperBound(distance);
            CurveSample previous = curve.get(high - 1);
            CurveSample current = curve.get(high);
            double span = current.distance() - previous.distance();
            if (span <= EPSILON) return current.position();
            return previous.position().lerp(current.position(), (distance - previous.distance()) / span);
        }

        /** 按弧长取归一化曲线切线。 */
        public Vec3 tangentAtDistance(double distance) {
            if (curve.isEmpty()) return direction;
            // GPS 航点精确到达时保留它要求的入场方向，避免同 Tick 取到出场段的切线。
            for (ScheduledRelease release : releases) {
                Vec3 preferred = release.preferredInboundDirection();
                double tolerance = EPSILON * Math.max(1.0D, Math.abs(release.distanceAlongRoute()));
                if (preferred != null && Math.abs(distance - release.distanceAlongRoute()) <= tolerance) {
                    Vec3 horizontal = horizontalDirection(preferred, direction);
                    return horizontal == null ? direction : horizontal;
                }
            }
            int index = Math.max(0, Math.min(curve.size() - 1, upperBound(Math.max(0.0D, distance))));
            Vec3 tangent = curve.get(index).tangent();
            return tangent.lengthSqr() > EPSILON ? tangent.normalize() : direction;
        }

        /** 返回参考曲线总弧长。 */
        public double totalDistance() {
            return curve.isEmpty() ? 0.0D : curve.get(curve.size() - 1).distance();
        }

        /** 返回指定 Tick 对应的参考弧长。 */
        public double distanceAt(long tick) {
            if (waypoints.isEmpty()) return 0.0D;
            if (tick <= waypoints.get(0).tick()) return 0.0D;
            for (int index = 1; index < waypoints.size(); index++) {
                Waypoint previous = waypoints.get(index - 1);
                Waypoint current = waypoints.get(index);
                if (tick <= current.tick()) {
                    double factor = current.tick() == previous.tick() ? 1.0D
                            : (double) (tick - previous.tick()) / (double) (current.tick() - previous.tick());
                    return Math.max(0.0D, Math.min(totalDistance(), previous.distance()
                            + (current.distance() - previous.distance()) * factor));
                }
            }
            return totalDistance();
        }

        private int upperBound(double distance) {
            int low = 0;
            int high = curve.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (curve.get(middle).distance() <= distance) low = middle + 1;
                else high = middle;
            }
            return Math.max(1, Math.min(curve.size() - 1, low));
        }
    }

    /** 参考飞机中心航点。 */
    public record Waypoint(
            /** 航点 Tick。 */ long tick,
            /** 航点位置。 */ Vec3 position,
            /** 航点对应的参考曲线弧长。 */ double distance) {}

    /** 平滑曲线采样点。 */
    public record CurveSample(
            /** 曲线位置。 */ Vec3 position,
            /** 从入场点开始的累计弧长。 */ double distance,
            /** 该点的归一化切线。 */ Vec3 tangent) {}

    /** 固定翼运动学参数；由本体公开字段或插件回退值构造。 */
    public record AircraftDynamics(
            /** 载机速度相关的转向倍率。 */ double turnRateBySpeed,
            /** 俯仰轴最大转速。 */ double pitchTurnRate,
            /** 偏航轴最大转速。 */ double yawTurnRate,
            /** 滚转轴最大转速。 */ double rollTurnRate) {
        /** 本体默认固定翼参数对应的插件回退值。 */
        public static AircraftDynamics fallback() {
            return new AircraftDynamics(0.4D, 2.0D, 3.0D, 8.0D);
        }
    }

    /** 飞机的连续运动学姿态。 */
    public record AircraftPose(
            /** 飞机中心位置。 */ Vec3 position,
            /** 飞机前向单位向量。 */ Vec3 forward,
            /** Minecraft 俯仰角。 */ float pitch,
            /** Minecraft 偏航角。 */ float yaw,
            /** Minecraft 滚转角。 */ float roll,
            /** 当前运动向量。 */ Vec3 motion) {}

    /**
     * 按固定翼可用转向速率推进一 Tick。位置沿当前实际前向移动，保持配置速度，不把实体
     * 直接拉回参考曲线；因此曲率超限时会产生连续且可由实时弹道补偿的航线偏差。
     */
    public static AircraftPose advance(AircraftPose current, Vec3 desiredDirection,
                                       double speed, AircraftDynamics dynamics) {
        if (current == null || desiredDirection == null || dynamics == null
                || !finite(desiredDirection) || desiredDirection.lengthSqr() <= EPSILON
                || !Double.isFinite(speed) || speed <= 0.0D) return current;
        Vec3 desired = desiredDirection.normalize();
        float desiredPitch = (float) Math.toDegrees(Math.atan2(-desired.y(),
                Math.sqrt(desired.x() * desired.x() + desired.z() * desired.z())));
        float desiredYaw = (float) Math.toDegrees(-Math.atan2(desired.x(), desired.z()));
        double speedFactor = Math.max(0.0D, speed * dynamics.turnRateBySpeed());
        float maxPitch = (float) Math.min(dynamics.pitchTurnRate(), speedFactor * dynamics.pitchTurnRate());
        float maxYaw = (float) Math.min(dynamics.yawTurnRate(), speedFactor * dynamics.yawTurnRate());
        float maxRoll = (float) Math.min(dynamics.rollTurnRate(), speedFactor * dynamics.rollTurnRate());
        // 目的：approachDegrees 返回的是新的绝对角度，先保存绝对偏航，再单独计算本 Tick 增量，
        // 避免把已经更新过的绝对角度再次加到当前角度上导致飞机入场时逐 Tick 发散旋转。
        float yaw = approachDegrees(current.yaw(), desiredYaw, maxYaw);
        float yawDelta = wrapDegrees(yaw - current.yaw());
        float pitch = approachDegrees(current.pitch(), desiredPitch, maxPitch);
        // 目的：固定翼滚转只响应本 Tick 的航向变化，不能把世界绝对偏航角当作滚转输入。
        // 目的：本体固定翼的 Minecraft ZRot 正方向与数学右手滚转视觉方向相反；取正号后，
        //       左转（偏航增量为负）表现为内侧左翼下沉，且挂架继续共享同一 pose.roll()。
        float targetRoll = Mth.clamp(yawDelta * 12.0F, -45.0F, 45.0F);
        float roll = approachDegrees(current.roll(), targetRoll, maxRoll);
        yaw = wrapDegrees(yaw);
        Vec3 forward = directionFromRotation(pitch, yaw);
        Vec3 motion = forward.scale(speed);
        return new AircraftPose(current.position().add(motion), forward, pitch, yaw, roll, motion);
    }

    /** 用各单发策略的释放点、公共入场段和平直出场段建立任务航线及原始 Tick 下限。 */
    public static RoutePlan plan(List<ReleaseTarget> targets,
                                 RVP_FireSupportDeliveryTypes.LocalOffset rackOffset,
                                 double entryDistanceMeters, double exitDistanceMeters,
                                 double carrierSpeedMetersPerTick, long actualStartTick) {
        return plan(targets, rackOffset, entryDistanceMeters, exitDistanceMeters,
                carrierSpeedMetersPerTick, AircraftDynamics.fallback(), actualStartTick);
    }

    /** 使用指定飞机的转向参数，为后续 GPS 释放点计算入场对准段并建立任务航线。 */
    public static RoutePlan plan(List<ReleaseTarget> targets,
                                 RVP_FireSupportDeliveryTypes.LocalOffset rackOffset,
                                 double entryDistanceMeters, double exitDistanceMeters,
                                 double carrierSpeedMetersPerTick, AircraftDynamics aircraftDynamics,
                                 long actualStartTick) {
        return planInternal(targets, rackOffset, entryDistanceMeters, exitDistanceMeters,
                carrierSpeedMetersPerTick, aircraftDynamics, actualStartTick, null, null);
    }

    /**
     * 按冻结原始出发点到任务锚点的水平坐标系重排纯无制导参考挂架航点，并建立任务航线。
     * 该入口只由空袭控制器在确认任务全部武器主、末段制导均为 NONE 后调用。
     */
    static RoutePlan planSpatiallyOrderedUnguided(
            List<ReleaseTarget> targets,
            RVP_FireSupportDeliveryTypes.LocalOffset rackOffset,
            double entryDistanceMeters, double exitDistanceMeters,
            double carrierSpeedMetersPerTick, AircraftDynamics aircraftDynamics,
            long actualStartTick, Vec3 taskAnchor) {
        return planInternal(targets, rackOffset, entryDistanceMeters, exitDistanceMeters,
                carrierSpeedMetersPerTick, aircraftDynamics, actualStartTick, taskAnchor, null);
    }

    /** 使用既有冻结出发点重建相同航点顺序，只允许起始 Tick 变化。 */
    static RoutePlan replanFromFixedEntry(
            List<ReleaseTarget> targets,
            RVP_FireSupportDeliveryTypes.LocalOffset rackOffset,
            double entryDistanceMeters, double exitDistanceMeters,
            double carrierSpeedMetersPerTick, AircraftDynamics aircraftDynamics,
            long actualStartTick, Vec3 fixedEntryPosition) {
        return planInternal(targets, rackOffset, entryDistanceMeters, exitDistanceMeters,
                carrierSpeedMetersPerTick, aircraftDynamics, actualStartTick, null, fixedEntryPosition);
    }

    /** 公共建线内核；taskAnchor 仅启用无制导空间排序，fixedEntryPosition 仅用于重定时。 */
    private static RoutePlan planInternal(
            List<ReleaseTarget> targets,
            RVP_FireSupportDeliveryTypes.LocalOffset rackOffset,
            double entryDistanceMeters, double exitDistanceMeters,
            double carrierSpeedMetersPerTick, AircraftDynamics aircraftDynamics,
            long actualStartTick, @Nullable Vec3 taskAnchor, @Nullable Vec3 fixedEntryPosition) {
        if (targets == null || targets.isEmpty() || rackOffset == null
                || !Double.isFinite(entryDistanceMeters) || entryDistanceMeters <= 0.0D
                || !Double.isFinite(exitDistanceMeters) || exitDistanceMeters <= 0.0D
                || !Double.isFinite(carrierSpeedMetersPerTick) || carrierSpeedMetersPerTick <= 0.0D
                || (taskAnchor != null && !finite(taskAnchor))
                || (fixedEntryPosition != null && !finite(fixedEntryPosition))) {
            return null;
        }
        ReleaseTarget first = targets.get(0);
        if (first == null || first.releasePosition() == null || first.inboundMotion() == null
                || !finite(first.releasePosition()) || !finite(first.inboundMotion())
                || first.inboundMotion().lengthSqr() <= EPSILON) return null;
        Vec3 direction = first.inboundMotion().normalize();
        AircraftDynamics dynamics = sanitizeDynamics(aircraftDynamics);
        try {
            List<ReleaseTarget> orderedTargets = new ArrayList<>(targets);
            List<Vec3> centers = new ArrayList<>(orderedTargets.size());
            for (ReleaseTarget target : targets) {
                if (target == null || target.releasePosition() == null || !finite(target.releasePosition())) return null;
                centers.add(target.releasePosition().subtract(rotateRack(rackOffset, direction)));
            }
            Vec3 entry = fixedEntryPosition == null
                    ? centers.get(0).subtract(direction.scale(entryDistanceMeters))
                    : fixedEntryPosition;
            if (taskAnchor != null) {
                // 调用本项目纯数学排序：只改变航点访问次序，并让参考点继续携带原轮次和计划 Tick。
                List<Integer> spatialOrder = spatialOrder(orderedTargets, entry, taskAnchor, direction);
                List<ReleaseTarget> spatialTargets = new ArrayList<>(orderedTargets.size());
                List<Vec3> spatialCenters = new ArrayList<>(centers.size());
                for (int originalIndex : spatialOrder) {
                    spatialTargets.add(orderedTargets.get(originalIndex));
                    spatialCenters.add(centers.get(originalIndex));
                }
                orderedTargets = spatialTargets;
                centers = spatialCenters;
            }
            Vec3 exit = centers.get(centers.size() - 1).add(direction.scale(exitDistanceMeters));
            List<Vec3> anchors = new ArrayList<>(centers.size() * 2 + 2);
            List<Boolean> straightSegments = new ArrayList<>(centers.size() * 2 + 1);
            List<Integer> releaseAnchorIndices = new ArrayList<>(orderedTargets.size());
            anchors.add(entry);
            Vec3 lastValidHeading = horizontalDirection(direction, direction);
            for (int index = 0; index < orderedTargets.size(); index++) {
                Vec3 center = centers.get(index);
                ReleaseTarget target = orderedTargets.get(index);
                Vec3 preferred = horizontalDirection(target.preferredInboundDirection(), null);
                if (index > 0 && preferred != null) {
                    Vec3 displacement = horizontalDirection(center.subtract(centers.get(index - 1)), null);
                    Vec3 incoming = displacement == null ? lastValidHeading : displacement;
                    if (incoming == null) incoming = preferred;
                    double leadDistance = calculateGpsTurnLeadDistance(
                            incoming, preferred, carrierSpeedMetersPerTick, dynamics);
                    if (leadDistance > EPSILON) {
                        anchors.add(center.subtract(preferred.scale(leadDistance)));
                        // 前序航点到对准点仍由平滑曲线转向；对准点到 GPS 中心严格沿期望来向飞行。
                        straightSegments.add(false);
                        anchors.add(center);
                        straightSegments.add(true);
                    } else {
                        anchors.add(center);
                        straightSegments.add(false);
                    }
                } else {
                    anchors.add(center);
                    // 首发 GPS 已从同一入场来向起飞，直线入场可避免后续 GPS 锚点扭曲首发对准段。
                    straightSegments.add(index == 0 && preferred != null);
                }
                releaseAnchorIndices.add(anchors.size() - 1);
                Vec3 displacement = index == 0 ? null
                        : horizontalDirection(center.subtract(centers.get(index - 1)), null);
                if (displacement != null) lastValidHeading = displacement;
                if (preferred != null) lastValidHeading = preferred;
            }
            anchors.add(exit);
            straightSegments.add(true);
            List<CurveSample> curve = buildCurve(anchors, direction, straightSegments);
            if (curve.size() < 2) return null;
            double[] anchorDistances = resolveAnchorDistances(curve, anchors);
            List<Waypoint> waypoints = new ArrayList<>(orderedTargets.size() + 2);
            waypoints.add(new Waypoint(actualStartTick, entry, 0.0D));
            List<ScheduledRelease> releases = new ArrayList<>(orderedTargets.size());
            long previousTick = actualStartTick;
            double previousDistance = 0.0D;
            for (int index = 0; index < orderedTargets.size(); index++) {
                ReleaseTarget target = orderedTargets.get(index);
                double distance = anchorDistances[releaseAnchorIndices.get(index)];
                long travelTicks = requiredTicks(distance - previousDistance, carrierSpeedMetersPerTick);
                long earliest = Math.addExact(previousTick, travelTicks);
                long actualTick = Math.max(target.plannedTick(), earliest);
                Vec3 center = centers.get(index);
                waypoints.add(new Waypoint(actualTick, center, distance));
                releases.add(new ScheduledRelease(target.roundIndex(), target.plannedTick(), actualTick,
                        target.releasePosition(), center, distance,
                        horizontalDirection(target.preferredInboundDirection(), null)));
                previousTick = actualTick;
                previousDistance = distance;
            }
            double totalDistance = curve.get(curve.size() - 1).distance();
            long endTick = Math.addExact(previousTick,
                    requiredTicks(totalDistance - previousDistance, carrierSpeedMetersPerTick));
            waypoints.add(new Waypoint(endTick, exit, totalDistance));
            return new RoutePlan(direction, (float) -Math.toDegrees(Math.atan2(direction.x(), direction.z())),
                    actualStartTick, endTick, waypoints, releases, curve);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 返回参考挂架航点的稳定空间次序：先比较主轴投影点到出发点的距离，再比较有符号垂距。
     * 右法向量为 (axis.z, 0, -axis.x)，因此数值升序表现为负侧远到近、再到正侧近到远。
     */
    private static List<Integer> spatialOrder(List<ReleaseTarget> targets, Vec3 entry,
                                              Vec3 taskAnchor, Vec3 fallbackDirection) {
        Vec3 axis = horizontalDirection(taskAnchor.subtract(entry), fallbackDirection);
        if (axis == null) throw new IllegalArgumentException("无制导航线空间排序主轴无效");
        Vec3 right = new Vec3(axis.z(), 0.0D, -axis.x());
        List<Integer> order = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) order.add(index);
        order.sort(Comparator
                .comparingDouble((Integer index) -> projectionDistance(
                        targets.get(index).releasePosition(), entry, axis))
                .thenComparingDouble(index -> signedLateralDistance(
                        targets.get(index).releasePosition(), entry, right))
                .thenComparingInt(Integer::intValue));
        return order;
    }

    /** 计算参考挂架航点投影到主轴后与出发点的水平距离。 */
    private static double projectionDistance(Vec3 point, Vec3 entry, Vec3 axis) {
        Vec3 delta = new Vec3(point.x() - entry.x(), 0.0D, point.z() - entry.z());
        return Math.abs(delta.dot(axis));
    }

    /** 计算参考挂架航点相对主轴右法向量的有符号水平距离。 */
    private static double signedLateralDistance(Vec3 point, Vec3 entry, Vec3 right) {
        Vec3 delta = new Vec3(point.x() - entry.x(), 0.0D, point.z() - entry.z());
        return delta.dot(right);
    }

    /** 构造含 GPS 对准直线段的采样曲线；straightSegments 与控制点区间一一对应。 */
    private static List<CurveSample> buildCurve(List<Vec3> anchors, Vec3 fallbackDirection,
                                                List<Boolean> straightSegments) {
        List<CurveSample> result = new ArrayList<>();
        double distance = 0.0D;
        Vec3 previous = anchors.get(0);
        Vec3 previousTangent = fallbackDirection;
        result.add(new CurveSample(previous, distance, previousTangent));
        for (int segment = 0; segment < anchors.size() - 1; segment++) {
            Vec3 p0 = segment == 0 ? anchors.get(segment).subtract(fallbackDirection)
                    : anchors.get(segment - 1);
            Vec3 p1 = anchors.get(segment);
            Vec3 p2 = anchors.get(segment + 1);
            Vec3 p3 = segment + 2 < anchors.size() ? anchors.get(segment + 2)
                    : anchors.get(segment + 1).add(fallbackDirection);
            // GPS 对准段和出场段都使用直线，保持挂架对准方向并避免末发后继续沿曲线绕行。
            boolean straightSegment = Boolean.TRUE.equals(straightSegments.get(segment));
            Vec3 straightDirection = p2.subtract(p1);
            if (straightDirection.lengthSqr() <= EPSILON) straightDirection = fallbackDirection;
            else straightDirection = straightDirection.normalize();
            for (int sample = 1; sample <= SAMPLES_PER_SEGMENT; sample++) {
                double u = (double) sample / SAMPLES_PER_SEGMENT;
                Vec3 position = straightSegment ? p1.lerp(p2, u) : catmullRom(p0, p1, p2, p3, u);
                if (!finite(position)) return List.of();
                double delta = position.distanceTo(previous);
                if (!Double.isFinite(delta)) return List.of();
                distance += delta;
                Vec3 tangent = straightSegment ? straightDirection : (sample + 1 <= SAMPLES_PER_SEGMENT
                        ? catmullRom(p0, p1, p2, p3,
                        Math.min(1.0D, u + 1.0D / SAMPLES_PER_SEGMENT)).subtract(position)
                        : position.subtract(previous));
                if (tangent.lengthSqr() <= EPSILON) tangent = previousTangent;
                else tangent = tangent.normalize();
                result.add(new CurveSample(position, distance, tangent));
                previous = position;
                previousTangent = tangent;
            }
        }
        return result;
    }

    /** 把控制点映射到采样曲线弧长；每个控制点都由对应区间末端采样精确命中。 */
    private static double[] resolveAnchorDistances(List<CurveSample> curve, List<Vec3> anchors) {
        double[] distances = new double[anchors.size()];
        for (int anchor = 0; anchor < anchors.size(); anchor++) {
            int sampleIndex = Math.min(curve.size() - 1, anchor * SAMPLES_PER_SEGMENT);
            distances[anchor] = curve.get(sampleIndex).distance();
        }
        return distances;
    }

    /** 向心 Catmull-Rom 单区间求值。 */
    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        double t0 = 0.0D;
        double t1 = t0 + Math.pow(Math.max(EPSILON, p0.distanceTo(p1)), CENTRIPETAL_ALPHA);
        double t2 = t1 + Math.pow(Math.max(EPSILON, p1.distanceTo(p2)), CENTRIPETAL_ALPHA);
        double t3 = t2 + Math.pow(Math.max(EPSILON, p2.distanceTo(p3)), CENTRIPETAL_ALPHA);
        double t = t1 + (t2 - t1) * Mth.clamp((float) u, 0.0F, 1.0F);
        Vec3 a1 = blend(p0, p1, t0, t1, t);
        Vec3 a2 = blend(p1, p2, t1, t2, t);
        Vec3 a3 = blend(p2, p3, t2, t3, t);
        Vec3 b1 = blend(a1, a2, t0, t2, t);
        Vec3 b2 = blend(a2, a3, t1, t3, t);
        return blend(b1, b2, t1, t2, t);
    }

    private static Vec3 blend(Vec3 first, Vec3 second, double firstTime, double secondTime, double time) {
        double denominator = secondTime - firstTime;
        if (Math.abs(denominator) <= EPSILON) return first;
        double factor = (time - firstTime) / denominator;
        return first.lerp(second, factor);
    }

    /** 按配置速度计算一段参考距离至少需要的 Tick 数。 */
    public static long requiredTicks(double distance, double speed) {
        if (!Double.isFinite(distance) || distance < -EPSILON) throw new IllegalArgumentException("路线距离无效");
        double ticks = Math.ceil(Math.max(0.0D, distance) / speed);
        if (!Double.isFinite(ticks) || ticks > Long.MAX_VALUE) throw new IllegalArgumentException("路线 Tick 溢出");
        return (long) ticks;
    }

    /** 按当前固定翼偏航限速估算转向航程，并附加 1.5 倍安全余量。 */
    static double calculateGpsTurnLeadDistance(Vec3 incomingDirection, Vec3 desiredDirection,
                                               double speed, AircraftDynamics dynamics) {
        Vec3 incoming = horizontalDirection(incomingDirection, null);
        Vec3 desired = horizontalDirection(desiredDirection, null);
        if (incoming == null || desired == null || !Double.isFinite(speed) || speed <= 0.0D) return 0.0D;
        AircraftDynamics safeDynamics = sanitizeDynamics(dynamics);
        double maximumYaw = maximumYawTurnPerTick(speed, safeDynamics);
        if (!Double.isFinite(maximumYaw) || maximumYaw <= EPSILON) return 0.0D;
        double dot = Mth.clamp(incoming.dot(desired), -1.0D, 1.0D);
        double angleDegrees = Math.toDegrees(Math.acos(dot));
        if (angleDegrees <= 1.0E-6D) return 0.0D;
        double turnTicks = Math.ceil(angleDegrees / maximumYaw);
        double distance = speed * turnTicks * 1.5D;
        if (!Double.isFinite(distance) || distance < 0.0D) {
            throw new IllegalArgumentException("GPS 转弯对准距离无效");
        }
        return distance;
    }

    /** 与 advance() 保持同一套速度倍率公式，返回每 Tick 最大偏航角。 */
    private static double maximumYawTurnPerTick(double speed, AircraftDynamics dynamics) {
        double speedFactor = Math.max(0.0D, speed * dynamics.turnRateBySpeed());
        return Math.min(dynamics.yawTurnRate(), speedFactor * dynamics.yawTurnRate());
    }

    /** 将缺失或非法的飞机动力参数回退为既有通用固定翼值。 */
    private static AircraftDynamics sanitizeDynamics(AircraftDynamics dynamics) {
        AircraftDynamics fallback = AircraftDynamics.fallback();
        if (dynamics == null) return fallback;
        return new AircraftDynamics(
                finitePositive(dynamics.turnRateBySpeed(), fallback.turnRateBySpeed()),
                finitePositive(dynamics.pitchTurnRate(), fallback.pitchTurnRate()),
                finitePositive(dynamics.yawTurnRate(), fallback.yawTurnRate()),
                finitePositive(dynamics.rollTurnRate(), fallback.rollTurnRate()));
    }

    /** 将可用的水平向量归一化；无效时尝试使用回退方向。 */
    @Nullable
    private static Vec3 horizontalDirection(@Nullable Vec3 value, @Nullable Vec3 fallback) {
        if (value != null && finite(value)) {
            Vec3 horizontal = new Vec3(value.x(), 0.0D, value.z());
            if (horizontal.lengthSqr() > EPSILON) return horizontal.normalize();
        }
        if (fallback != null && finite(fallback)) {
            Vec3 horizontalFallback = new Vec3(fallback.x(), 0.0D, fallback.z());
            if (horizontalFallback.lengthSqr() > EPSILON) return horizontalFallback.normalize();
        }
        return null;
    }

    private static double finitePositive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static Vec3 rotateRack(RVP_FireSupportDeliveryTypes.LocalOffset offset, Vec3 direction) {
        double heading = Math.atan2(direction.x(), direction.z());
        double cos = Math.cos(heading);
        double sin = Math.sin(heading);
        return new Vec3(cos * offset.x() + sin * offset.z(), offset.y(),
                -sin * offset.x() + cos * offset.z());
    }

    private static Vec3 directionFromRotation(float pitch, float yaw) {
        double pitchRadians = Math.toRadians(pitch);
        double yawRadians = Math.toRadians(-yaw);
        double horizontal = Math.cos(pitchRadians);
        return new Vec3(Math.sin(yawRadians) * horizontal, -Math.sin(pitchRadians),
                Math.cos(yawRadians) * horizontal).normalize();
    }

    private static float approachDegrees(float current, float target, float maximumStep) {
        if (!Float.isFinite(maximumStep) || maximumStep <= 0.0F) return current;
        float delta = wrapDegrees(target - current);
        return current + Mth.clamp(delta, -maximumStep, maximumStep);
    }

    private static float wrapDegrees(float value) {
        return Mth.wrapDegrees(value);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
