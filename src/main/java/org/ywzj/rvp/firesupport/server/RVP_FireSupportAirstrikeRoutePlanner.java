package org.ywzj.rvp.firesupport.server;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;

/**
 * 空中支援公共任务航线的纯数学规划器；不读取世界、不生成实体、不持有服务端状态。
 *
 * <p>GPS 与普通弹道策略先分别提供每发释放点，本实现再统一反推飞机中心、规划入场点和
 * 出场点，并以向心 Catmull-Rom 曲线连接中段；最后一个投放点到出场点使用直线段。
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
            /** 原始入场方向和载机速度向量。 */ Vec3 inboundMotion) {}

    /** 一发弹对应的平滑航线参考点。 */
    public record ScheduledRelease(
            /** 任务内全局轮次。 */ int roundIndex,
            /** 原始计划投放 Tick。 */ long plannedTick,
            /** 参考曲线经过该点时的实际 Tick；不得早于原始 Tick。 */ long actualTick,
            /** 原始空投释放点。 */ Vec3 releasePosition,
            /** 由挂架偏移反推的参考飞机中心。 */ Vec3 aircraftCenter,
            /** 参考曲线上的弧长位置。 */ double distanceAlongRoute) {}

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
        if (targets == null || targets.isEmpty() || rackOffset == null
                || !Double.isFinite(entryDistanceMeters) || entryDistanceMeters <= 0.0D
                || !Double.isFinite(exitDistanceMeters) || exitDistanceMeters <= 0.0D
                || !Double.isFinite(carrierSpeedMetersPerTick) || carrierSpeedMetersPerTick <= 0.0D) {
            return null;
        }
        ReleaseTarget first = targets.get(0);
        if (first == null || first.releasePosition() == null || first.inboundMotion() == null
                || !finite(first.releasePosition()) || !finite(first.inboundMotion())
                || first.inboundMotion().lengthSqr() <= EPSILON) return null;
        Vec3 direction = first.inboundMotion().normalize();
        try {
            List<Vec3> centers = new ArrayList<>(targets.size());
            for (ReleaseTarget target : targets) {
                if (target == null || target.releasePosition() == null || !finite(target.releasePosition())) return null;
                centers.add(target.releasePosition().subtract(rotateRack(rackOffset, direction)));
            }
            Vec3 entry = centers.get(0).subtract(direction.scale(entryDistanceMeters));
            Vec3 exit = centers.get(centers.size() - 1).add(direction.scale(exitDistanceMeters));
            List<Vec3> anchors = new ArrayList<>(centers.size() + 2);
            anchors.add(entry);
            anchors.addAll(centers);
            anchors.add(exit);
            List<CurveSample> curve = buildCurve(anchors, direction);
            if (curve.size() < 2) return null;
            double[] anchorDistances = resolveAnchorDistances(curve, anchors);
            List<Waypoint> waypoints = new ArrayList<>(targets.size() + 2);
            waypoints.add(new Waypoint(actualStartTick, entry, 0.0D));
            List<ScheduledRelease> releases = new ArrayList<>(targets.size());
            long previousTick = actualStartTick;
            double previousDistance = 0.0D;
            for (int index = 0; index < targets.size(); index++) {
                ReleaseTarget target = targets.get(index);
                double distance = anchorDistances[index + 1];
                long travelTicks = requiredTicks(distance - previousDistance, carrierSpeedMetersPerTick);
                long earliest = Math.addExact(previousTick, travelTicks);
                long actualTick = Math.max(target.plannedTick(), earliest);
                Vec3 center = centers.get(index);
                waypoints.add(new Waypoint(actualTick, center, distance));
                releases.add(new ScheduledRelease(target.roundIndex(), target.plannedTick(), actualTick,
                        target.releasePosition(), center, distance));
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

    /** 构造平滑入场/投放曲线与直线出场段的弧长采样表。 */
    private static List<CurveSample> buildCurve(List<Vec3> anchors, Vec3 fallbackDirection) {
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
            // 最后一个参考投放点到出场点必须是直线，避免末发后继续沿平滑曲线绕行。
            boolean straightExitLeg = segment == anchors.size() - 2;
            Vec3 exitDirection = p2.subtract(p1);
            if (exitDirection.lengthSqr() <= EPSILON) exitDirection = fallbackDirection;
            else exitDirection = exitDirection.normalize();
            for (int sample = 1; sample <= SAMPLES_PER_SEGMENT; sample++) {
                double u = (double) sample / SAMPLES_PER_SEGMENT;
                Vec3 position = straightExitLeg ? p1.lerp(p2, u) : catmullRom(p0, p1, p2, p3, u);
                if (!finite(position)) return List.of();
                double delta = position.distanceTo(previous);
                if (!Double.isFinite(delta)) return List.of();
                distance += delta;
                Vec3 tangent = straightExitLeg ? exitDirection : (sample + 1 <= SAMPLES_PER_SEGMENT
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
