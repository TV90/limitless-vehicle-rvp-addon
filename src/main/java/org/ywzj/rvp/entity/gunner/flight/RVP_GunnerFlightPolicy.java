package org.ywzj.rvp.entity.gunner.flight;

import org.ywzj.rvp.flight.RVP_FlightMath;
import org.ywzj.rvp.flight.RVP_FlightRequest;
import org.ywzj.rvp.flight.RVP_FlightRequest.*;
import org.ywzj.rvp.flight.RVP_FlightTypes.Vector;

/** Gunner纯业务航路策略；不计算油门/杆量，不操作世界或武器。 */
public final class RVP_GunnerFlightPolicy {
    private RVP_GunnerFlightPolicy() {}

    /** Gunner任务阶段，公共控制器不感知这些枚举。 */
    public enum Phase {
        /** 起飞爬升。 */ TAKEOFF, /** 无敌巡逻。 */ PATROL, /** 接近目标。 */ APPROACH,
        /** 具备攻击几何。 */ ATTACK, /** 拉开并重整。 */ DISENGAGE, /** 返回任务区。 */ RETURN,
        /** 反雷达飞离。 */ SEAD_AWAY, /** 反雷达回转。 */ SEAD_TOWARD
    }
    /** 航路输入，武器有效范围由业务只读查询传入。 */
    public record Input(
            /** 本业务稳定目标标识。 */ String goalId,
            /** 服务器刻。 */ long tick,
            /** 本机位置。 */ Vector position,
            /** 当前航向。 */ double yaw,
            /** 完整速度，格/秒。 */ double speed,
            /** 本刻离地高度。 */ double agl,
            /** 是否地面。 */ boolean grounded,
            /** home位置。 */ Vector home,
            /** 有效导航目标标识；无目标为-1。 */ int targetId,
            /** 目标位置，无目标时为null。 */ Vector target,
            /** 目标速度，格/秒。 */ Vector velocity,
            /** 选中武器是否制导。 */ boolean guided,
            /** 目标是否飞机。 */ boolean airTarget,
            /** 该目标是否只是近防弹药。 */ boolean interception,
            /** 已存任务目标是否死亡/移除。 */ boolean missionDestroyed,
            /** 武器距离下限，格。 */ double minimumRange,
            /** 武器距离上限，格。 */ double maximumRange,
            /** 业务巡航最低离地高度。 */ double altitudeMin,
            /** 业务巡航最高离地高度。 */ double altitudeMax,
            /** 业务水平作战范围上限，0关闭。 */ double combatRadius,
            /** 攻击超时上限，刻。 */ int attackTimeout,
            /** 脱离超时上限，刻。 */ int disengageTimeout,
            /** 0普通、1反雷达飞离、2反雷达回转。 */ int seadMode) {}

    /** 纯业务状态，不保存游戏对象。 */
    public record State(
            /** 当前任务阶段。 */ Phase phase,
            /** 本阶段开始刻。 */ long phaseSince,
            /** 最后观察到导航目标的刻。 */ long lastTargetTick,
            /** 导航目标标识。 */ int missionId,
            /** 最近有效目标位置。 */ Vector lastTarget,
            /** 最近有效目标速度。 */ Vector lastVelocity,
            /** 任务版本，目标或阶段变化时递增。 */ long revision,
            /** 导航任务的武器类别，不被近防临时目标改写。 */ boolean guided,
            /** 导航任务是否对空。 */ boolean airTarget,
            /** 导航任务的武器距离下限，格。 */ double minimumRange,
            /** 导航任务的武器距离上限，格。 */ double maximumRange,
            /** 起飞阶段锁定的初始滑跑航向。 */ double takeoffHeading) {
        /** 初始状态。 */
        public static State initial() { return new State(Phase.PATROL, 0, Long.MIN_VALUE, -1, null, null, 0, false, false, 0, 600, 0); }
    }
    /** 本业务本刻结果；开火仍需已执行公共反馈和武器链确认。 */
    public record Decision(
            /** 参数化公共请求。 */ RVP_FlightRequest request,
            /** 下一业务状态。 */ State state,
            /** 业务几何是否允许尝试攻击。 */ boolean attackGeometry) {}

    /** 只在该业务的移动候选胜出后运行，落选候选不推动阶段。 */
    public static Decision decide(Input in, State previous) {
        int missionId = previous.missionId();
        Vector target = previous.lastTarget(), velocity = previous.lastVelocity();
        long lastSeen = previous.lastTargetTick();
        boolean hasNavigationTarget = in.target() != null && !in.interception();
        boolean guided = hasNavigationTarget ? in.guided() : previous.guided();
        boolean airTarget = hasNavigationTarget ? in.airTarget() : previous.airTarget();
        double minimumRange = hasNavigationTarget ? in.minimumRange() : previous.minimumRange();
        double maximumRange = hasNavigationTarget ? in.maximumRange() : previous.maximumRange();
        if (hasNavigationTarget) { missionId = in.targetId(); target = in.target(); velocity = in.velocity(); lastSeen = in.tick(); }
        if (in.missionDestroyed() || !hasNavigationTarget && (lastSeen == Long.MIN_VALUE || in.tick() - lastSeen > 60)) {
            missionId = -1; target = null; velocity = null;
        }
        double distanceHome = in.position().subtract(in.home()).horizontalLength();
        double targetDistance = target == null ? Double.POSITIVE_INFINITY : target.subtract(in.position()).horizontalLength();
        double minimumTurnSpace = Math.max(80, in.speed() * in.speed() / (RVP_FlightMath.GRAVITY * Math.tan(Math.toRadians(40))) * 0.3);
        double breakDistance = Math.max(minimumTurnSpace, minimumRange * 1.2);
        Phase phase = previous.phase();
        if (in.grounded() || in.agl() < Math.min(40, in.altitudeMin())) phase = Phase.TAKEOFF;
        else if (in.combatRadius() > 0 && (distanceHome > in.combatRadius()
                || previous.phase() == Phase.RETURN && distanceHome > in.combatRadius() * 0.75)) phase = Phase.RETURN;
        else if (in.seadMode() == 1) phase = Phase.SEAD_AWAY;
        else if (in.seadMode() == 2) phase = Phase.SEAD_TOWARD;
        else if (target == null) phase = Phase.PATROL;
        else {
            boolean changed = missionId != previous.missionId();
            if (changed || phase != Phase.APPROACH && phase != Phase.ATTACK && phase != Phase.DISENGAGE) phase = Phase.APPROACH;
            long elapsed = in.tick() - previous.phaseSince();
            if (targetDistance < breakDistance || phase == Phase.ATTACK && elapsed >= in.attackTimeout()) phase = Phase.DISENGAGE;
            else if (phase == Phase.DISENGAGE) {
                if (targetDistance > breakDistance * 2.5 || elapsed >= in.disengageTimeout()) phase = Phase.APPROACH;
            } else if (targetDistance <= Math.max(breakDistance * 1.5, maximumRange * 0.9)) phase = Phase.ATTACK;
        }
        long revision = previous.revision() + (phase != previous.phase() || missionId != previous.missionId() ? 1 : 0);
        long phaseSince = phase != previous.phase() || missionId != previous.missionId() ? in.tick() : previous.phaseSince();
        double takeoffHeading = previous.phase() == Phase.TAKEOFF ? previous.takeoffHeading() : in.yaw();
        double desiredAgl = phase == Phase.ATTACK ? in.altitudeMin() : (in.altitudeMin() + in.altitudeMax()) * 0.5;
        Altitude altitude = Altitude.agl(desiredAgl);
        Goal goal;
        if (phase == Phase.TAKEOFF) goal = new Takeoff(takeoffHeading);
        else if (phase == Phase.PATROL) {
            double radius = in.combatRadius() > 0 ? Math.max(80, in.combatRadius() * 0.45) : 250;
            goal = new Orbit(in.home(), radius, 1);
        } else if (phase == Phase.RETURN) {
            double heading = RVP_FlightMath.yawTo(in.home().x() - in.position().x(), in.home().z() - in.position().z());
            goal = new FlyTo(in.home(), Math.max(40, in.combatRadius() * 0.25), heading);
        } else if (phase == Phase.DISENGAGE || phase == Phase.SEAD_AWAY) {
            Vector away = target == null ? new Vector(-Math.sin(Math.toRadians(in.yaw())), 0, Math.cos(Math.toRadians(in.yaw())))
                    : in.position().subtract(target);
            double heading = away.horizontalLength() > 1 ? RVP_FlightMath.yawTo(away.x(), away.z()) : in.yaw();
            goal = new HoldCourse(heading);
        } else {
            goal = new TrackPoint(target, velocity == null ? new Vector(0, 0, 0) : velocity, lastSeen,
                    guided ? 0.25 : 0.5, 0);
            // 对空跟随目标高度；非制导对地安排有限下降路线，其余维持业务巡航高度。
            if (airTarget) altitude = Altitude.worldY(target.y());
            else if (!guided && phase == Phase.ATTACK)
                altitude = Altitude.worldY(target.y() + Math.max(30, Math.min(in.altitudeMin(), targetDistance * 0.2)));
        }
        Limits limits = Limits.cruiseDefaults().withMaxBankDeg(phase == Phase.PATROL ? 30 : 45)
                .withAltitudeLimits(20, in.position().y() - in.agl() + in.altitudeMax());
        RVP_FlightRequest request = new RVP_FlightRequest(in.goalId(), revision, goal, altitude,
                Speed.safeCruise(phase == Phase.PATROL ? 1.25 : 1.35), limits);
        boolean attack = hasNavigationTarget && (phase == Phase.ATTACK || phase == Phase.SEAD_TOWARD)
                && targetDistance >= breakDistance && targetDistance <= maximumRange;
        return new Decision(request, new State(phase, phaseSince, lastSeen, missionId, target, velocity, revision,
                guided, airTarget, minimumRange, maximumRange, takeoffHeading), attack);
    }
}
