package org.ywzj.rvp.flight;

import java.util.Objects;
import java.util.UUID;

/** 公共飞行数据合同；记录不可变，计算状态与业务状态分离。 */
public final class RVP_FlightTypes {
    private RVP_FlightTypes() {}

    /** 三维数值向量；位置单位格，速度单位格/秒，由字段用途明确区分。 */
    public record Vector(
            /** 世界X分量。 */ double x,
            /** 世界Y分量。 */ double y,
            /** 世界Z分量。 */ double z) {
        /** 业务样本可暂缺分量；请求边界会降级目标，真实飞机观察边界则必须阻止坏值。 */
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
        /** 向量相加，用于业务目标的有限运动外推。 */
        public Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        /** 向量相减，用于相对位置。 */
        public Vector subtract(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
        /** 按比例缩放，用于秒数与速度换算位移。 */
        public Vector scale(double factor) { return new Vector(x * factor, y * factor, z * factor); }
        /** 水平长度，不把高度差混进作战范围。 */
        public double horizontalLength() { return Math.hypot(x, z); }
        /** 完整空间长度，用于本体气动速度。 */
        public double length() { return Math.hypot(Math.hypot(x, y), z); }
    }

    /** 地形只读观察；未知部分必须看对应标志，数值0不表示已确认平地。 */
    public record Terrain(
            /** 脚下地表世界Y；currentKnown为true才有效。 */ double groundY,
            /** 前方样本中的最高世界Y；aheadKnown为true才完整可信。 */ double aheadY,
            /** 当前地表是否已确认。 */ boolean currentKnown,
            /** 有限前视路径样本是否全部已加载并确认。 */ boolean aheadKnown) {
        public Terrain {
            // 无效地形样本转成未知，控制器仍会保守继续，不把它误当已知平地。
            currentKnown &= Double.isFinite(groundY); aheadKnown &= Double.isFinite(aheadY);
            groundY = Double.isFinite(groundY) ? groundY : 0;
            aheadY = Double.isFinite(aheadY) ? aheadY : 0;
        }
    }

    /** 单个物理刻的只读飞机快照；俯仰正值抬头，坡度正值右倾。 */
    public record Observation(
            /** 载具持久UUID。 */ UUID vehicleId,
            /** 维度资源标识字符串。 */ String dimension,
            /** 服务器物理刻编号。 */ long tick,
            /** 当前控制会话代次。 */ long generation,
            /** 世界位置，格。 */ Vector position,
            /** 完整速度，格/秒。 */ Vector velocity,
            /** Minecraft航向，度。 */ double yawDeg,
            /** 抬头角，度。 */ double pitchUpDeg,
            /** 右倾角，度。 */ double bankRightDeg,
            /** 右倾角变化率，度/秒。 */ double bankRateDegPerSecond,
            /** 当前油门百分比。 */ double throttlePercent,
            /** 本体地面转向分支是否生效。 */ boolean onGround,
            /** 已完成的有限地形观察。 */ Terrain terrain) {
        public Observation {
            Objects.requireNonNull(vehicleId); Objects.requireNonNull(dimension);
            Objects.requireNonNull(position); Objects.requireNonNull(velocity);
            // 只将真实飞机的非有限坐标/速度视为致命数值；业务目标坏值由请求层尽量修正。
            if (!position.finite() || !velocity.finite()) throw new IllegalArgumentException("真实飞机位置/速度必须为有限数值");
            terrain = terrain == null ? new Terrain(0, 0, false, false) : terrain;
            RVP_FlightMath.finite(yawDeg, "yawDeg");
            RVP_FlightMath.finite(pitchUpDeg, "pitchUpDeg");
            RVP_FlightMath.finite(bankRightDeg, "bankRightDeg");
            // 派生角速度和油门读数可恢复；真实位置/速度/姿态的非有限数值仍属致命边界。
            bankRateDegPerSecond = RVP_FlightMath.bounded(bankRateDegPerSecond, 0, -100000, 100000);
            throttlePercent = RVP_FlightMath.bounded(throttlePercent, 50, 0, 1000);
        }
    }

    /** 本刻可用机体参数；角响应已按高度和当前速度换成每单位操纵量的度/刻。 */
    public record Airframe(
            /** 质量，kg。 */ double massKg,
            /** 当前可用推力，N，已乘发动机功率。 */ double thrustN,
            /** 最低阻力系数。 */ double dragMin,
            /** 最高阻力系数。 */ double dragMax,
            /** 本体升阻比参数。 */ double liftToDrag,
            /** 本刻空气影响系数。 */ double airScale,
            /** 负迎角下限，度。 */ double aoaMinDeg,
            /** 正迎角上限，度。 */ double aoaMaxDeg,
            /** 俯仰响应，度/刻/单位杆量。 */ double pitchRate,
            /** 偏航响应，度/刻/单位杆量。 */ double yawRate,
            /** 滚转响应，度/刻/单位杆量。 */ double rollRate,
            /** 允许的机体油门硬上限百分比。 */ double maximumThrottle) {
        public Airframe {
            // 调用公共修正补齐估算参数；零推力/零轴响应是实际能力，须保留并继续尽力操纵其他轴。
            massKg = RVP_FlightMath.bounded(massKg > 0 ? massKg : Double.NaN, 4074, 0.001, 1.0E10);
            thrustN = RVP_FlightMath.bounded(thrustN, 24444, 0, 1.0E12);
            dragMin = RVP_FlightMath.bounded(dragMin > 0 ? dragMin : Double.NaN, 16.296, 0.000001, 1.0E9);
            dragMax = RVP_FlightMath.bounded(dragMax, Math.max(dragMin, 24.444), dragMin, 1.0E9);
            liftToDrag = RVP_FlightMath.bounded(liftToDrag > 0 ? liftToDrag : Double.NaN, 3, 0.001, 1.0E6);
            airScale = RVP_FlightMath.bounded(airScale, 1, 0.001, 10);
            aoaMinDeg = RVP_FlightMath.bounded(aoaMinDeg, -10, -89, 0);
            aoaMaxDeg = RVP_FlightMath.bounded(aoaMaxDeg, 25, 0.1, 89);
            pitchRate = RVP_FlightMath.bounded(pitchRate, 1.2, 0, 90);
            yawRate = RVP_FlightMath.bounded(yawRate, 1.8, 0, 90);
            rollRate = RVP_FlightMath.bounded(rollRate, 2.4, 0, 90);
            maximumThrottle = RVP_FlightMath.bounded(maximumThrottle, 100, 0, 1000);
        }
        /** 初次观察机体参数失败时的保守估算；后续每刻仍重新读取实际机体能力。 */
        public static Airframe conservative() {
            return new Airframe(4074, 24444, 16.296, 24.444, 3, 1, -10, 25, 1.2, 1.8, 2.4, 100);
        }
    }

    /** 公共到位/风险状态，不包含任何业务开火许可。 */
    public enum Status {
        /** 正在跟随目标。 */ TRACKING,
        /** 连续满足几何和速度高度要求。 */ CAPTURED,
        /** 已通过航点，继续沿离开方向飞。 */ REACHED,
        /** 正在恢复安全状态。 */ RECOVERING,
        /** 已按可行方式继续执行，原请求受能力或约束限制；不表示拒绝或要求重试。 */ LIMITED,
        /** 目标已保存，等待当前持有者交还控制后自动执行。 */ WAITING
    }

    /** 可选部件动作；KEEP意味着不接管此部件。 */
    public enum Switch {
        /** 保持原状。 */ KEEP,
        /** 打开部件。 */ ON,
        /** 关闭部件。 */ OFF
    }

    /** 控制器状态；只有本刻指令成功执行后才提交。 */
    public record State(
            /** 逻辑目标标识。 */ String goalId,
            /** 目标业务版本。 */ long revision,
            /** 上次成功执行刻。 */ long tick,
            /** 已通过当前航点。 */ boolean reached,
            /** 连续稳定到位刻数。 */ int stableTicks,
            /** 安全恢复保持剩余刻数。 */ int recoveryTicks,
            /** 正后方转弯的稳定方向，正值右转。 */ int turnSign,
            /** 上次已执行抬头操纵量。 */ double pitch,
            /** 上次已执行右转操纵量。 */ double yaw,
            /** 上次已执行右倾操纵量。 */ double roll,
            /** 上次执行时的位置，用于高速通过航点检测。 */ Vector lastPosition,
            /** 本目标首次实际执行时的航向，用于缺省方向的稳定保持。 */ double referenceHeadingDeg,
            /** 本目标首次实际执行时的高度，用于缺省高度的稳定保持。 */ double referenceAltitudeY) {
        public State {
            Objects.requireNonNull(goalId); Objects.requireNonNull(lastPosition);
            if (!lastPosition.finite()) throw new IllegalArgumentException("历史飞机位置必须为有限数值");
            if (stableTicks < 0 || recoveryTicks < 0 || turnSign != 1 && turnSign != -1)
                throw new IllegalArgumentException("控制状态计数或转弯方向无效");
            RVP_FlightMath.range(pitch, -1, 1, "previousPitch");
            RVP_FlightMath.range(yaw, -1, 1, "previousYaw");
            RVP_FlightMath.range(roll, -1, 1, "previousRoll");
            RVP_FlightMath.finite(referenceHeadingDeg, "referenceHeading");
            RVP_FlightMath.finite(referenceAltitudeY, "referenceAltitude");
        }
        /** 初次接管或长时间未执行后重新捕获，不复活旧累计状态。 */
        public static State empty() { return new State("", -1, Long.MIN_VALUE, false, 0, 0, 1, 0, 0, 0, new Vector(0, 0, 0), 0, 0); }
    }

    /** 本刻完整运动指令；语义正值依次为抬头、右转、右倾。 */
    public record Command(
            /** 载具UUID。 */ UUID vehicleId,
            /** 所属维度。 */ String dimension,
            /** 计算来源刻；执行器会以当前物理刻的实际步长重新翻译。 */ long tick,
            /** 接管代次，旧代次不能写入新持有者。 */ long generation,
            /** 逻辑目标标识。 */ String goalId,
            /** 业务版本。 */ long revision,
            /** 抬头杆量，[-1,1]。 */ double pitch,
            /** 右转杆量，[-1,1]。 */ double yaw,
            /** 右倾杆量，[-1,1]。 */ double roll,
            /** 本刻明确的油门百分比。 */ double throttlePercent,
            /** 起落架开关；ON为本体收起。 */ Switch landingGear,
            /** 减速板开关。 */ Switch airbrake) {
        public Command {
            Objects.requireNonNull(vehicleId); Objects.requireNonNull(dimension);
            goalId = goalId == null || goalId.isBlank() ? "anonymous" : goalId;
            landingGear = landingGear == null ? Switch.KEEP : landingGear;
            airbrake = airbrake == null ? Switch.KEEP : airbrake;
            // 调用公共修正，指令缺失某轴时取中立，绝不把NaN写入实体。
            pitch = RVP_FlightMath.bounded(pitch, 0, -1, 1);
            yaw = RVP_FlightMath.bounded(yaw, 0, -1, 1);
            roll = RVP_FlightMath.bounded(roll, 0, -1, 1);
            throttlePercent = RVP_FlightMath.bounded(throttlePercent, 50, 0, 1000);
        }
    }

    /** 公共反馈，业务必须同时确认真实执行结果和版本。 */
    public record Feedback(
            /** 计算状态。 */ Status status,
            /** 可读限制原因。 */ String reason,
            /** 逻辑目标。 */ String goalId,
            /** 业务版本。 */ long revision,
            /** 对应物理刻。 */ long tick,
            /** 高度误差，格。 */ double altitudeError,
            /** 速度误差，格/秒。 */ double speedError,
            /** 航向误差，度。 */ double headingError,
            /** 用户请求半径，非绕圈时为0。 */ double requestedRadius,
            /** 本刻物理可行半径，非绕圈时为0。 */ double effectiveRadius) {}

    /** 一次无副作用计算的完整结果。 */
    public record Solution(
            /** 交给执行器的唯一操纵指令。 */ Command command,
            /** 不含业务许可的计算反馈。 */ Feedback feedback,
            /** 执行成功后才保存的状态。 */ State nextState) {}
}
