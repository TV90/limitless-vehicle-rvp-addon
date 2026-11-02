package org.ywzj.rvp.flight;

import org.ywzj.rvp.flight.RVP_FlightTypes.Vector;
import org.ywzj.rvp.flight.RVP_FlightTypes.Switch;

/** 强类型飞行请求；构造时修正可恢复参数，之后保持不可变，不反写业务配置。 */
public record RVP_FlightRequest(
        /** 业务逻辑目标，连续更新位置时保持不变。 */ String goalId,
        /** 目标/阶段更换时递增。 */ long revision,
        /** 目标几何。 */ Goal goal,
        /** 明确高度类型和值。 */ Altitude altitude,
        /** 明确速度类型和值。 */ Speed speed,
        /** 业务飞行限制。 */ Limits limits,
        /** 起落架动作，默认KEEP。 */ Switch landingGear,
        /** 减速板动作，默认KEEP。 */ Switch airbrake) {
    public RVP_FlightRequest {
        goalId = goalId == null || goalId.isBlank() ? "anonymous" : goalId;
        revision = Math.max(0, revision);
        // 缺少目标几何时先稳飞；没有坐标不能猜成世界原点，后续补齐目标即可平滑继续。
        if (goal == null || goal instanceof FlyTo fly && fly.point() == null
                || goal instanceof Orbit orbit && orbit.center() == null
                || goal instanceof TrackPoint track && track.point() == null) goal = new Stabilize();
        // 调用公共默认参数补齐可选项；当前高度由控制器首次实际观察确定。
        altitude = altitude == null ? Altitude.current() : altitude;
        speed = speed == null ? Speed.safeCruise(1.25) : speed;
        limits = limits == null ? Limits.cruiseDefaults() : limits;
        landingGear = landingGear == null ? Switch.KEEP : landingGear;
        airbrake = airbrake == null ? Switch.KEEP : airbrake;
    }

    /** 常用构造：不接管起落架和减速板。 */
    public RVP_FlightRequest(String id, long version, Goal goal, Altitude altitude, Speed speed, Limits limits) {
        this(id, version, goal, altitude, speed, limits, Switch.KEEP, Switch.KEEP);
    }

    /** 通用几何目标，无作战/UAV专属模式。 */
    public sealed interface Goal permits Stabilize, HoldCourse, FlyTo, Orbit, TrackPoint, Takeoff {}

    /** 未提供导航几何时保持接管时航向，仍执行业务指定高度、速度和部件动作。 */
    public record Stabilize() implements Goal {}

    /** 持续保持业务指定的航向。 */
    public record HoldCourse(/** 航向，度。 */ double headingDeg) implements Goal {
        /** 非有限航向表示保持接管时航向，不能把缺失方向猜成正南。 */
        public HoldCourse { headingDeg = Double.isFinite(headingDeg) ? RVP_FlightMath.wrap(headingDeg) : Double.NaN; }
    }
    /** 飞过航点后沿离开航向保持飞行，不能在空中停车。 */
    public record FlyTo(/** 航点，世界坐标。 */ Vector point,
                        /** 通过容许距离，格。 */ double arrivalRadius,
                        /** 通过后的离开航向，度。 */ double exitHeadingDeg) implements Goal {
        public FlyTo {
            // 调用坐标修正，超出世界可用范围时飞向最近边界，不因距离过大放弃导航。
            point = navigationPoint(point);
            // 调用公共修正：负距离取幅值，缺失距离使用20格；缺失离开方向留给实际观察补齐。
            arrivalRadius = RVP_FlightMath.bounded(Math.abs(arrivalRadius), 20, 1, 100000);
            exitHeadingDeg = Double.isFinite(exitHeadingDeg) ? RVP_FlightMath.wrap(exitHeadingDeg) : Double.NaN;
        }
    }
    /** 跟随圆；方向+1向右，-1向左。 */
    public record Orbit(/** 圆心位置。 */ Vector center,
                        /** 请求半径，格。 */ double radius,
                        /** 绕圈方向，负数向左，其余向右，保存为±1。 */ int direction) implements Goal {
        public Orbit {
            // 调用坐标修正保留可用圆心；null会由完整请求解释为稳飞。
            center = navigationPoint(center);
            // 调用公共修正；具体能否飞出此半径仍由控制器按本机能力放宽。
            radius = RVP_FlightMath.bounded(Math.abs(radius), 150, 1, 100000);
            direction = direction < 0 ? -1 : 1;
        }
    }
    /** 跟随业务提供的运动参考点，不读取目标实体或解算武器弹道。 */
    public record TrackPoint(/** 参考点位置。 */ Vector point,
                             /** 参考点速度，格/秒。 */ Vector velocity,
                             /** 采样服务器刻。 */ long sampleTick,
                             /** 最长外推时间，秒。 */ double predictionSeconds,
                             /** 期望跟随间隔，格。 */ double spacing) implements Goal {
        public TrackPoint {
            // 调用坐标修正，避免极大有限数在外推中溢出。
            point = navigationPoint(point);
            // 未提供速度就跟随最后位置；外推过长只缩短预测，不丢弃整个目标。
            velocity = velocity == null ? new Vector(0, 0, 0) : velocity;
            velocity = new Vector(RVP_FlightMath.bounded(velocity.x(), 0, -2000, 2000),
                    RVP_FlightMath.bounded(velocity.y(), 0, -2000, 2000),
                    RVP_FlightMath.bounded(velocity.z(), 0, -2000, 2000));
            predictionSeconds = RVP_FlightMath.bounded(predictionSeconds, 0.5, 0, 3);
            spacing = RVP_FlightMath.bounded(Math.abs(spacing), 0, 0, 100000);
        }
    }
    /** 按已确认的起飞方向滑跑与爬升，不自动寻找跑道。 */
    public record Takeoff(/** 滑跑方向，度。 */ double headingDeg) implements Goal {
        /** 未给有效方向时使用当前机头方向，不替业务搜索跑道。 */
        public Takeoff { headingDeg = Double.isFinite(headingDeg) ? RVP_FlightMath.wrap(headingDeg) : Double.NaN; }
    }
    /** 高度类型与值，避免把AGL误用成世界Y。 */
    public record Altitude(/** true表示离地高度，false表示世界Y。 */ boolean aboveGround,
                           /** 高度值，格；keepCurrent时不使用。 */ double value,
                           /** 是否保持本目标首次执行时的世界高度。 */ boolean keepCurrent) {
        public Altitude {
            keepCurrent |= !Double.isFinite(value);
            // 调用公共修正，未知高度保留明确标志，不误解为世界Y=0。
            value = RVP_FlightMath.bounded(value, 0, aboveGround ? 0 : -100000, 100000);
        }
        /** 保留明确指定高度的常用构造。 */
        public Altitude(boolean aboveGround, double value) { this(aboveGround, value, false); }
        /** 自动保持首次实际执行高度。 */
        public static Altitude current() { return new Altitude(false, 0, true); }
        /** 明确构造离地高度。 */
        public static Altitude agl(double value) { return new Altitude(true, value); }
        /** 明确构造世界高度。 */
        public static Altitude worldY(double value) { return new Altitude(false, value); }
    }
    /** 速度指定方式，倍率和实际速度不能混用。 */
    public record Speed(/** true为最低可飞速度倍率。 */ boolean minimumSpeedFactor,
                        /** 倍率或格/秒，取决于类型。 */ double value) {
        public Speed {
            // 调用公共修正；低于可飞速度的请求随后还会按实际升力要求上调。
            value = RVP_FlightMath.bounded(value, minimumSpeedFactor ? 1.25 : 35,
                    minimumSpeedFactor ? 1.05 : 1, minimumSpeedFactor ? 3 : 2000);
        }
        /** 由本机气动估计最低速度后乘安全倍率。 */
        public static Speed safeCruise(double factor) { return new Speed(true, factor); }
        /** 明确的格/秒目标。 */
        public static Speed metresPerSecond(double value) { return new Speed(false, value); }
    }
    /** 业务限制：超出数值边界自动取最近可用值，颠倒的上下限自动交换。 */
    public record Limits(
            /** 最大倾斜角，度，公共硬上限60。 */ double maxBankDeg,
            /** 最大抬头角，度。 */ double maxPitchUpDeg,
            /** 最大低头角，正值幅值，度。 */ double maxPitchDownDeg,
            /** 最大爬升速度，格/秒。 */ double maxClimbMps,
            /** 最大下降速度，正值幅值，格/秒。 */ double maxDescentMps,
            /** 最低地形净空，格。 */ double minimumClearance,
            /** 业务允许的世界Y下限。 */ double minimumWorldY,
            /** 业务允许的世界Y上限；安全恢复可临时优先避地。 */ double maximumWorldY,
            /** 业务允许的最低速度，格/秒；0仅表示不附加高于机体升力底线的要求。 */ double minimumSpeedMps,
            /** 业务允许的最高速度，格/秒；升力安全冲突会报告受限而非静默服从。 */ double maximumSpeedMps,
            /** 是否允许本机加力。 */ boolean allowAfterburner) {
        public Limits {
            // 调用公共修正补齐缺省值并限制幅值，避免一个参数影响整条业务请求。
            maxBankDeg = RVP_FlightMath.bounded(Math.abs(maxBankDeg), 35, 5, 60);
            maxPitchUpDeg = RVP_FlightMath.bounded(Math.abs(maxPitchUpDeg), 18, 5, 35);
            maxPitchDownDeg = RVP_FlightMath.bounded(Math.abs(maxPitchDownDeg), 10, 0, 25);
            maxClimbMps = RVP_FlightMath.bounded(Math.abs(maxClimbMps), 4, 0.1, 30);
            maxDescentMps = RVP_FlightMath.bounded(Math.abs(maxDescentMps), 4, 0.1, 20);
            minimumClearance = RVP_FlightMath.bounded(minimumClearance, 20, 10, 2048);
            double firstY = RVP_FlightMath.bounded(minimumWorldY, -100000, -100000, 100000);
            double secondY = RVP_FlightMath.bounded(maximumWorldY, 4096, -100000, 100000);
            minimumWorldY = Math.min(firstY, secondY); maximumWorldY = Math.max(firstY, secondY);
            double firstSpeed = RVP_FlightMath.bounded(minimumSpeedMps, 0, 0, 2000);
            double secondSpeed = RVP_FlightMath.bounded(maximumSpeedMps, 2000, 0, 2000);
            minimumSpeedMps = Math.min(firstSpeed, secondSpeed);
            maximumSpeedMps = Math.max(1, Math.max(firstSpeed, secondSpeed));
        }
        /** 默认温和巡航，不使用加力。 */
        public static Limits cruiseDefaults() { return new Limits(35, 18, 10, 4, 4, 20, -100000, 4096, 0, 2000, false); }
        /** 不可变覆盖倾斜角；其他参数继续沿用。 */
        public Limits withMaxBankDeg(double value) {
            return new Limits(value, maxPitchUpDeg, maxPitchDownDeg, maxClimbMps, maxDescentMps,
                    minimumClearance, minimumWorldY, maximumWorldY, minimumSpeedMps, maximumSpeedMps, allowAfterburner);
        }
        /** 不可变覆盖地形净空和高度上限。 */
        public Limits withAltitudeLimits(double clearance, double ceiling) {
            return new Limits(maxBankDeg, maxPitchUpDeg, maxPitchDownDeg, maxClimbMps, maxDescentMps,
                    clearance, minimumWorldY, ceiling, minimumSpeedMps, maximumSpeedMps, allowAfterburner);
        }
        /** 不可变覆盖世界高度区间，AGL任务同样受此区间和地形保护约束。 */
        public Limits withWorldAltitudeRange(double minimumY, double maximumY) {
            return new Limits(maxBankDeg, maxPitchUpDeg, maxPitchDownDeg, maxClimbMps, maxDescentMps,
                    minimumClearance, minimumY, maximumY, minimumSpeedMps, maximumSpeedMps, allowAfterburner);
        }
        /** 不可变覆盖速度区间；不足以维持升力的业务上限不会被当成安全飞行要求。 */
        public Limits withSpeedRange(double minimumMps, double maximumMps) {
            return new Limits(maxBankDeg, maxPitchUpDeg, maxPitchDownDeg, maxClimbMps, maxDescentMps,
                    minimumClearance, minimumWorldY, maximumWorldY, minimumMps, maximumMps, allowAfterburner);
        }
    }

    /** 只修正导航坐标，不篡改真实载具观察；采用Minecraft可用水平范围和公共高度数值范围。 */
    private static Vector navigationPoint(Vector point) {
        // 坐标无法定位时由完整请求转为保持航向，仍执行可用的高度、速度和部件请求。
        if (point == null || !point.finite()) return null;
        // 调用公共限幅保持坐标有限，业务可读取构造后的请求了解实际采用值。
        return new Vector(RVP_FlightMath.clamp(point.x(), -30000000, 30000000),
                RVP_FlightMath.clamp(point.y(), -100000, 100000),
                RVP_FlightMath.clamp(point.z(), -30000000, 30000000));
    }
}
