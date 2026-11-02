package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterPhase;

/**
 * 无人机盘旋制导算法。
 * <p>固定翼控制通过每 tick 补偿本体 inputStep 连续写入模拟杆量，避免无人驾驶空挡时本体自动
 * 俯仰/偏航/滚转回正分支抢写控制。布尔输入只用于维持本体非空挡通道；油门仍按本体的累加语义
 * 做互斥调节。旋翼机沿用目标航向模拟控制。</p>
 * <p>半径规划用载具质量、阻力、升阻比、高度 scaleAir 和迎角估计可行转弯速度与有效半径；
 * 最小速度采用 5° 迎角近似并加 12%余量，不代表本体完整气动模型的精确失速速度。飞控每 tick
 * 同时用垂向速度误差和协调转弯前馈控制俯仰/偏航，油门按速度与高度误差互斥调节。</p>
 */
public final class RVP_UavLoiterGuidance {

    private RVP_UavLoiterGuidance() {}

    /** 偏航布尔通道启用电平（度）：超过才用偏航杆强对齐，小误差交滚转闭环。 */
    private static final float YAW_BOOLEAN_THRESHOLD = 30.0F;
    /** 坡度闭环死区（度）。 */
    private static final float BANK_DEADBAND = 3.0F;
    /** 对齐阶段坡度比例增益：航向误差（度）→ 目标坡度（度）。 */
    private static final float BANK_PROPORTIONAL_GAIN = 0.6F;
    /** 径向修正封顶（度）：距圆偏差对目标坡度的修正上限。 */
    private static final float RADIAL_CORRECTION_CAP = 10.0F;
    /** 垂直速度闭环：每格高度误差折算的目标爬升率（m/s）。 */
    private static final double CLIMB_RATE_PER_ALT_ERROR = 0.08;
    /** 垂直速度闭环死区（m/s）。 */
    private static final double CLIMB_RATE_DEADBAND = 0.6;
    /** 盘旋速度闭环：超速判定（目标速度 × 本系数 以上收油）。 */
    private static final double SPEED_OVER_FACTOR = 1.08;
    /** 盘旋速度闭环：欠速判定（目标速度 × 本系数 以下给油）。 */
    private static final double SPEED_UNDER_FACTOR = 0.9;

    /**
     * 盘旋切线航向（按盘旋方向取向，2026-10-06 方向配对修复）。
     * <p>推导（MC yaw 系 look=(−sinYaw,cosYaw)，yaw+ 顺时针）：右盘旋（俯视顺时针，圆东点朝南）
     * 切线 = atan2(dz,dx)（圆东点验证：atan2(0,+ρ)=0 → 朝南 ✓）；左盘旋 = atan2(−dz,−dx)
     * （圆东点 → 朝北）。旧版恒用 atan2(−dz,−dx)=左转圆切线，配右压坡度 → 坡度与圆周反向
     * → 向外倾斜、半径越盘越大、切不进圆。</p>
     */
    static double tangentYaw(double dx, double dz, int direction) {
        return direction >= 0
                ? Math.toDegrees(Math.atan2(dz, dx))
                : Math.toDegrees(Math.atan2(-dz, -dx));
    }

    /** 与本体 VectorUtil.vecToRot 一致：yaw=-atan2(dx,dz)，前向为 (-sin(yaw), cos(yaw))。 */
    static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * 固定翼对齐/盘旋通用的节流阀脉冲：超解算速度收油（刚性，占空高）；欠速或需爬升给油。
     * 旧版 CLIMB/TRANSIT 无条件 forward=true 满油门——高速下升力富余数倍重力，直接放飞上飘。
     */
    private static boolean[] fixedWingThrottle(double altError, double hSpeedMps,
                                               double solutionSpeedMps, int tickCount) {
        boolean backward = hSpeedMps > solutionSpeedMps * SPEED_OVER_FACTOR && pulseThrottle(tickCount, 4);
        boolean forward = !backward && (altError > 5 || hSpeedMps < solutionSpeedMps * SPEED_UNDER_FACTOR)
                && pulseThrottle(tickCount, 2);
        return new boolean[]{forward, backward};
    }

    /** 制导输出，描述要写入 ControlUnit 的字段值。 */
    public record GuidanceOutput(
            boolean forward,
            boolean backward,
            boolean up,
            boolean down,
            boolean left,
            boolean right,
            boolean leftYaw,
            boolean rightYaw,
            float targetYRot,
            boolean useAnalogYaw,
            float pitchInput,
            float yawInput,
            float rollInput,
            LoiterPhase nextPhase
    ) {}

    /** 固定翼连续模拟杆量闭环。 */
    public record FixedWingAnalogControls(float pitch, float yaw, float roll) {}

    public static FixedWingAnalogControls computeFixedWingAnalogControls(float headingErrorDeg,
                                                                          float targetBankDeg,
                                                                          float currentBankDeg,
                                                                          double altitudeError,
                                                                          double climbRateMps,
                                                                          double coordinatedTurnRateDegPerTick,
                                                                          double pitchRateDegPerInput,
                                                                          double yawRateDegPerInput) {
        double targetClimbRate = Mth.clamp(altitudeError * CLIMB_RATE_PER_ALT_ERROR, -4.0, 4.0);
        double climbRateError = targetClimbRate - climbRateMps;
        // 本体按局部 Z→X→Y 依序旋转；倾斜转弯要持续局部俯仰角速度抵消 yaw 的 Euler 耦合。
        // 对近水平坡度，bodyPitchRate ≈ -yawRate × sin(bank)，按本体单 tick 俯仰响应换算输入。
        double turnPitchInput = pitchRateDegPerInput > 1.0E-6
                ? -coordinatedTurnRateDegPerTick * Math.sin(Math.toRadians(currentBankDeg)) / pitchRateDegPerInput
                : 0.0;
        float pitch = (float) Mth.clamp(-climbRateError * 0.16 + turnPitchInput, -0.9, 0.9);
        float bankError = targetBankDeg - currentBankDeg;
        float roll = Math.abs(bankError) < BANK_DEADBAND ? 0.0F
                : Mth.clamp(bankError * 0.035F, -0.85F, 0.85F);
        double yawFeedForward = yawRateDegPerInput > 1.0E-6
                ? -coordinatedTurnRateDegPerTick * Math.cos(Math.toRadians(currentBankDeg)) / yawRateDegPerInput
                : 0.0;
        float yaw = (float) Mth.clamp(yawFeedForward
                + (Math.abs(headingErrorDeg) < 3.0F ? 0.0 : -headingErrorDeg * 0.008), -0.85, 0.85);
        return new FixedWingAnalogControls(pitch, yaw, roll);
    }

    /** 固定翼布尔杆量闭环输出（纯函数，便于单元测试）。 */
    public record FixedWingControls(
            boolean leftYaw,
            boolean rightYaw,
            boolean rollLeft,
            boolean rollRight,
            boolean up,
            boolean down
    ) {}

    /**
     * 可持续盘旋解（纯函数）：实际半径 / 目标坡度（度，带符号前的幅值） / 目标速度（m/s）。
     *
     * @param configuredRadius 配置盘旋半径（格，权威意图）
     * @param vMinMps          最小平飞速度（m/s，= sqrt(G·m/(k_min·liftToDrag))；≤0 表示气动参数不可得，走几何回退）
     * @param hSpeedBlocks     当前空速（blocks/tick，仅几何回退分支使用）
     */
    public record FixedWingLoiterSolution(double actualRadius, double targetBankDeg, double targetSpeedMps) {}

    /** 预补偿后的杆量值；消费后必须等于 target，避免本体空输入的自动回正逻辑介入。 */
    public record CompensatedInput(float setterValue, float target) {}

    /** 计算 setter 应写的值；fixed-wing tickMove 随后叠加 signedStep。 */
    public static CompensatedInput compensateInput(float target, float signedStep) {
        float clamped = Mth.clamp(target, -1.0F, 1.0F);
        return new CompensatedInput(clamped - signedStep, clamped);
    }

    /** 按 FixedWingVehicle.tickMove 的速度平方阻力、scaleAir 与迎角升力区间估计升力下限。 */
    public static double estimateFixedWingMinSpeedMps(double massKg, double airDragKMin,
                                                        double airDragKMax, double liftToDrag,
                                                        double scaleAir, double aoaDeg,
                                                        double aoaMaxDeg) {
        if (!(massKg > 0) || !(airDragKMin > 0) || !(liftToDrag > 0)
                || !(aoaMaxDeg > 0) || !Double.isFinite(scaleAir)) return 0.0;
        double scale = Math.max(0.001, scaleAir);
        double alpha = Mth.clamp((float) aoaDeg, -90.0F, 90.0F);
        double dragK = (airDragKMax - airDragKMin) * Math.abs(Math.sin(Math.toRadians(alpha)))
                + airDragKMin;
        double liftFactor = liftToDrag * scale + 2.0 * scale * alpha / aoaMaxDeg;
        if (!(liftFactor > 0) || !(dragK > 0)) return 0.0;
        double blockSpeed = Math.sqrt(org.ywzj.vehicle.vehicle.PhysicsEngine.G * massKg
                / (dragK * liftFactor));
        return blockSpeed * 20.0 * 1.12;
    }

    /**
     * 解出满足升力约束的盘旋（半径， 坡度， 速度）。
     * <p>升力约束推导：盘旋坡度 φ 下垂直升力 = L·cosφ ≥ 重力，其中 L/W = (v/v_min)²，
     * 联立 R = v²/(g·tanφ) 得临界条件 sinφ ≥ v_min²/(g·R)。解：φ = asin(v_min²/(gR)) 封顶
     * {@link RVP_LoiterConfig#LOITER_BANK}；超上限时 R = v_min²/(g·sinφ_cap) 物理兜底；
     * 目标速度 v = sqrt(R·g·tanφ) 且不低于 v_min。</p>
     */
    public static FixedWingLoiterSolution resolveFixedWingLoiterSolution(
            double configuredRadius, double vMinMps, double hSpeedBlocks) {
        double radius = Math.max(configuredRadius, 1.0);
        double bankCap = RVP_LoiterConfig.LOITER_BANK;
        double bankDeg;
        if (vMinMps > 0) {
            double sinPhi = (vMinMps * vMinMps) / (radius * 9.8);
            if (sinPhi <= Math.sin(Math.toRadians(bankCap))) {
                // 配置半径可行：坡度取升力临界值（不低于 5°，保证有效转弯）
                bankDeg = Math.max(Math.toDegrees(Math.asin(sinPhi)), 5.0);
            } else {
                // 物理兜底：坡度封顶，半径放大到升力约束的最小值
                bankDeg = bankCap;
                radius = (vMinMps * vMinMps) / (9.8 * Math.sin(Math.toRadians(bankDeg)));
            }
        } else {
            // 气动参数不可得回退：按当前空速几何反算（旧版行为，但不设 max(80,…) 硬下限）
            double v = hSpeedBlocks * 20.0;
            double geoBank = Math.toDegrees(Math.atan(v * v / (9.8 * radius)));
            if (geoBank > bankCap) {
                bankDeg = bankCap;
                radius = Math.max((v * v) / (9.8 * Math.tan(Math.toRadians(bankDeg))), configuredRadius);
            } else {
                bankDeg = geoBank;
            }
        }
        double phi = Math.toRadians(Math.max(bankDeg, 1.0));
        double targetSpeed = Math.sqrt(radius * 9.8 * Math.tan(phi));
        if (vMinMps > 0) {
            targetSpeed = Math.max(targetSpeed, vMinMps);
        }
        return new FixedWingLoiterSolution(radius, bankDeg, targetSpeed);
    }

    /**
     * 固定翼布尔杆量闭环（纯函数）。
     *
     * @param headingErrorDeg 航向误差（wrap 后）：期望 − 当前，正值 = MC yaw 增大方向
     * @param targetBankDeg   目标坡度（带符号）：负 = 左坡度（左转）
     * @param currentBankDeg  当前滚转角（uav.getZRot()，带符号）：负 = 左坡度
     * @param altError        高度误差（目标 − 当前，正 = 需爬升，格）
     * @param climbRateMps    实际垂直速度（m/s，deltaMovement.y × 20）
     * @param tickCount       全局 tick（脉冲相位）
     */
    public static FixedWingControls computeFixedWingControls(float headingErrorDeg, float targetBankDeg,
                                                             float currentBankDeg, double altError,
                                                             double climbRateMps, int tickCount) {
        // 本体 FixedWingVehicle 将 leftYaw 映射为正 q.rotateY，再以 -rot.y 写回实体 yaw；
        // 因而 leftYaw 会减小 yaw，正 yaw 误差必须使用 rightYaw。
        boolean leftYaw = headingErrorDeg < -YAW_BOOLEAN_THRESHOLD;
        boolean rightYaw = headingErrorDeg > YAW_BOOLEAN_THRESHOLD;
        float bankError = targetBankDeg - currentBankDeg;
        boolean rollLeft = false;
        boolean rollRight = false;
        if (bankError > BANK_DEADBAND || bankError < -BANK_DEADBAND) {
            boolean pulse = pulseDuty(tickCount, Math.abs(bankError));
            // 按 left 使 zRot 向负方向累积（左坡度），按 right 向正方向累积（右坡度）
            rollLeft = bankError < 0 && pulse;
            rollRight = bankError > 0 && pulse;
        }
        // 高度二级闭环：目标爬升率 → 垂直速度误差 → 脉冲（垂直速度即时阻尼）
        double targetClimbRate = Mth.clamp(altError * CLIMB_RATE_PER_ALT_ERROR, -4.0, 4.0);
        double climbRateError = targetClimbRate - climbRateMps;
        boolean up = climbRateError > CLIMB_RATE_DEADBAND && climbPulse(tickCount, climbRateError);
        boolean down = climbRateError < -CLIMB_RATE_DEADBAND && climbPulse(tickCount, -climbRateError);
        return new FixedWingControls(leftYaw, rightYaw, rollLeft, rollRight, up, down);
    }

    /** 按坡度误差大小取占空（10 tick 周期）：&gt;20 → 4/10，&gt;10 → 2/10，其余 1/10；≤死区不脉冲。 */
    private static boolean pulseDuty(int tickCount, double magnitude) {
        if (magnitude < BANK_DEADBAND) {
            return false;
        }
        int period = 10;
        int duty;
        if (magnitude > 20) {
            duty = 4;
        } else if (magnitude > 10) {
            duty = 2;
        } else {
            duty = 1;
        }
        return (tickCount % period) < duty;
    }

    /** 垂直速度误差占空（10 tick 周期）：&gt;4 → 5/10，&gt;2 → 3/10，&gt;1 → 2/10，其余 1/10。 */
    private static boolean climbPulse(int tickCount, double climbRateError) {
        int period = 10;
        int duty;
        if (climbRateError > 4) {
            duty = 5;
        } else if (climbRateError > 2) {
            duty = 3;
        } else if (climbRateError > 1) {
            duty = 2;
        } else {
            duty = 1;
        }
        return (tickCount % period) < duty;
    }

    /**
     * 固定翼脉冲式节流阀控制。
     * <p>本体飞控的 forward/backward 是累加式的（每tick油门±5）。
     * 脉冲式控制避免油门持续变化。</p>
     */
    private static boolean pulseThrottle(int tickCount, int duty) {
        return (tickCount % 10) < duty;
    }

    /**
     * CLIMB 阶段：爬升到安全高度。
     */
    public static GuidanceOutput computeClimb(double uavX, double uavY, double uavZ, float uavYaw,
                                              double centerX, double centerY, double centerZ,
                                              double targetAltitude, double climbRateMps,
                                              double hSpeedMps, double solutionSpeedMps,
                                              boolean isRotaryWing, int tickCount, float currentBankDeg) {
        double altError = targetAltitude - uavY;
        boolean needClimb = uavY < targetAltitude - 20 || altError > 20;

        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double targetYaw = yawTo(dx, dz);

        if (isRotaryWing) {
            return new GuidanceOutput(false, false, true, false, false, false, false, false,
                    uavYaw, true, 0, 0, 0, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
        }
        float headingError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
        float targetBank = Mth.clamp(headingError * BANK_PROPORTIONAL_GAIN,
                -(float) RVP_LoiterConfig.ALIGN_BANK_CAP, (float) RVP_LoiterConfig.ALIGN_BANK_CAP);
        FixedWingControls controls = computeFixedWingControls(headingError, targetBank, currentBankDeg,
                altError, climbRateMps, tickCount);
        boolean[] throttle = fixedWingThrottle(altError, hSpeedMps, solutionSpeedMps, tickCount);
        FixedWingAnalogControls analog = computeFixedWingAnalogControls(headingError, targetBank,
                currentBankDeg, altError, climbRateMps, 0.0, 0.0, 0.0);
        return new GuidanceOutput(throttle[0], throttle[1], controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) targetYaw, false, analog.pitch(), analog.yaw(), analog.roll(),
                needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
    }

    /**
     * TRANSIT 阶段：朝盘旋圆周接入点直飞。
     */
    public static GuidanceOutput computeTransit(double uavX, double uavY, double uavZ, float uavYaw,
                                                double centerX, double centerY, double centerZ,
                                                double radius, double targetAltitude, double climbRateMps,
                                                double hSpeedMps, double solutionSpeedMps,
                                                boolean isRotaryWing, int tickCount, float currentBankDeg) {
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            dist = 0.001;
        }
        double approachX = centerX - (dx / dist) * radius;
        double approachZ = centerZ - (dz / dist) * radius;
        double targetYaw = yawTo(approachX - uavX, approachZ - uavZ);

        double altError = targetAltitude - uavY;
        LoiterPhase nextPhase = dist < radius * 1.5 ? LoiterPhase.APPROACH : LoiterPhase.TRANSIT;

        if (isRotaryWing) {
            boolean up = altError > 5;
            boolean down = altError < -5;
            return new GuidanceOutput(true, false, up, down, false, false, false, false,
                    (float) targetYaw, true, 0, 0, 0, nextPhase);
        }
        float headingError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
        float targetBank = Mth.clamp(headingError * BANK_PROPORTIONAL_GAIN,
                -(float) RVP_LoiterConfig.ALIGN_BANK_CAP, (float) RVP_LoiterConfig.ALIGN_BANK_CAP);
        FixedWingControls controls = computeFixedWingControls(headingError, targetBank, currentBankDeg,
                altError, climbRateMps, tickCount);
        boolean[] throttle = fixedWingThrottle(altError, hSpeedMps, solutionSpeedMps, tickCount);
        FixedWingAnalogControls analog = computeFixedWingAnalogControls(headingError, targetBank,
                currentBankDeg, altError, climbRateMps, 0.0, 0.0, 0.0);
        return new GuidanceOutput(throttle[0], throttle[1], controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) targetYaw, false, analog.pitch(), analog.yaw(), analog.roll(), nextPhase);
    }

    /**
     * APPROACH 阶段：对齐切线，准备进入盘旋。
     */
    public static GuidanceOutput computeApproach(double uavX, double uavY, double uavZ, float uavYaw,
                                                 double centerX, double centerY, double centerZ,
                                                 double radius, double targetAltitude, double climbRateMps,
                                                 int loiterDirection, boolean isRotaryWing, int tickCount,
                                                 float currentBankDeg, double hSpeedMps, double solutionSpeedMps) {
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            dist = 0.001;
        }

        double tangent = tangentYaw(uavX - centerX, uavZ - centerZ, loiterDirection);
        double toCenterYaw = yawTo(dx, dz);
        double blend = Mth.clamp((float) ((dist - radius) / (radius * 0.5)), 0f, 1f);
        double targetYaw = lerpAngle(tangent, toCenterYaw, blend);

        double altError = targetAltitude - uavY;
        boolean captured = Math.abs(dist - radius) < Math.max(12.0, radius * 0.12)
                && Math.abs(altError) < 12.0
                && Math.abs(Mth.wrapDegrees((float) (tangent - uavYaw))) < 18.0
                && (solutionSpeedMps <= 0 || hSpeedMps >= solutionSpeedMps * 0.88);
        LoiterPhase nextPhase = captured ? LoiterPhase.LOITER : LoiterPhase.APPROACH;

        if (isRotaryWing) {
            boolean up = altError > 3;
            boolean down = altError < -3;
            int duty = (int) (4 * blend + 1);
            boolean forward = (tickCount % 5) < duty;
            return new GuidanceOutput(forward, false, up, down, false, false, false, false,
                    (float) targetYaw, true, 0, 0, 0, nextPhase);
        }
        float headingError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
        float targetBank = Mth.clamp(headingError * BANK_PROPORTIONAL_GAIN,
                -(float) RVP_LoiterConfig.ALIGN_BANK_CAP, (float) RVP_LoiterConfig.ALIGN_BANK_CAP);
        FixedWingControls controls = computeFixedWingControls(headingError, targetBank, currentBankDeg,
                altError, climbRateMps, tickCount);
        boolean[] throttle = fixedWingThrottle(altError, hSpeedMps, solutionSpeedMps, tickCount);
        FixedWingAnalogControls analog = computeFixedWingAnalogControls(headingError, targetBank,
                currentBankDeg, altError, climbRateMps, 0.0, 0.0, 0.0);
        return new GuidanceOutput(throttle[0], throttle[1], controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) targetYaw, false, analog.pitch(), analog.yaw(), analog.roll(), nextPhase);
    }

    /**
     * LOITER 阶段：盘旋。
     * <p>固定翼：目标坡度 = 方向 ×（解算坡度 + 径向修正）；节流阀双向闭环——超目标速度收油
     * （保半径/坡度自洽）、欠速或需爬升给油。</p>
     *
     * @param uavZRot        载具当前 Z 旋转（坡度），用于坡度闭环
     * @param climbRateMps   实际垂直速度（m/s）
     * @param hSpeedMps      当前空速（m/s，水平分量）
     * @param solutionBank   解算坡度（度，正值幅值，来自 resolveFixedWingLoiterSolution）
     * @param solutionSpeed  解算目标速度（m/s，来自 resolveFixedWingLoiterSolution）
     */
    public static GuidanceOutput computeLoiter(double uavX, double uavY, double uavZ, float uavYaw,
                                               double centerX, double centerY, double centerZ,
                                               double radius, double targetAltitude, double climbRateMps,
                                               boolean isRotaryWing, int tickCount,
                                               float uavZRot, double hSpeedMps,
                                               double solutionBank, double solutionSpeed) {
        return computeLoiter(uavX, uavY, uavZ, uavYaw, centerX, centerY, centerZ, radius,
                targetAltitude, climbRateMps, isRotaryWing, tickCount, uavZRot, hSpeedMps,
                solutionBank, solutionSpeed, 1.0, 1.0);
    }

    /** 固定翼连续盘旋控制的速度相关偏航/俯仰协调增益。 */
    public static GuidanceOutput computeLoiter(double uavX, double uavY, double uavZ, float uavYaw,
                                               double centerX, double centerY, double centerZ,
                                               double radius, double targetAltitude, double climbRateMps,
                                               boolean isRotaryWing, int tickCount,
                                               float uavZRot, double hSpeedMps,
                                               double solutionBank, double solutionSpeed,
                                               double pitchRateDegPerInput, double yawRateDegPerInput) {
        double dx = uavX - centerX;
        double dz = uavZ - centerZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.001) {
            horizontalDist = 0.001;
        }
        double radialError = horizontalDist - radius;

        // 切线航向（按盘旋方向取向：direction=1 右盘旋/俯视顺时针，−1 左盘旋）
        double tangentYaw = tangentYaw(dx, dz, RVP_LoiterConfig.LOITER_DIRECTION);

        // 高度控制（旋翼：总距脉冲；固定翼由 computeFixedWingControls 的垂直速度闭环接管）
        double altError = targetAltitude - uavY;
        boolean up = false;
        boolean down = false;
        if (isRotaryWing) {
            up = altError > 2;
            down = altError < -2;
            if (Math.abs(altError) < 8) {
                int duty = (int) Mth.clamp((float) (Math.abs(altError) / 2), 1f, 4f);
                boolean pulse = (tickCount % 5) < duty;
                up = altError > 2 && pulse;
                down = altError < -2 && pulse;
            }
        }

        if (isRotaryWing) {
            boolean forward = (radialError > 5 || horizontalDist < radius * 0.5);
            float yawError = Mth.wrapDegrees((float) (tangentYaw - uavYaw));
            yawError += RVP_LoiterConfig.LOITER_DIRECTION * Mth.clamp((float) radialError * 0.12f, -35f, 35f);
            float targetYRot = Mth.wrapDegrees(uavYaw + yawError);
            return new GuidanceOutput(forward, false, up, down, false, false, false, false,
                    targetYRot, true, 0, 0, 0, LoiterPhase.LOITER);
        }

        // 固定翼盘旋：目标坡度 = 方向 ×（解算坡度 + 径向修正，外侧收紧）
        float radialCorrection = Mth.clamp((float) radialError * 0.2f, -RADIAL_CORRECTION_CAP, RADIAL_CORRECTION_CAP);
        boolean lowSpeedRecovery = solutionSpeed > 0 && hSpeedMps < solutionSpeed * 0.84;
        boolean droppingBelowTarget = altError > 12.0 && climbRateMps < -1.5;
        boolean excessiveBank = Math.abs(uavZRot) > RVP_LoiterConfig.LOITER_BANK + 5.0;
        boolean enterRecovery = lowSpeedRecovery || droppingBelowTarget || excessiveBank;
        float targetBank = (lowSpeedRecovery || excessiveBank) ? 0.0F : Mth.clamp(
                RVP_LoiterConfig.LOITER_DIRECTION * ((float) solutionBank + radialCorrection),
                -45f, 45f);
        float headingError = Mth.wrapDegrees((float) (tangentYaw - uavYaw));

        // 节流阀双向闭环：超解算速度收油（速度是半径/坡度/升力自洽的前提）；
        // 欠速或高度不足给油。收油优先级（占空）高于给油，保证速度上限刚性。
        boolean backward = hSpeedMps > solutionSpeed * SPEED_OVER_FACTOR && pulseThrottle(tickCount, 4);
        boolean throttleForClimb = droppingBelowTarget && hSpeedMps < solutionSpeed * 1.1;
        boolean throttleForSpeed = (hSpeedMps < solutionSpeed * SPEED_UNDER_FACTOR
                || (altError > 5 && hSpeedMps < solutionSpeed * 1.15))
                && pulseThrottle(tickCount, 2);
        boolean forward = !backward && (lowSpeedRecovery || throttleForClimb || throttleForSpeed);

        FixedWingControls controls = computeFixedWingControls(headingError, targetBank,
                Mth.wrapDegrees(uavZRot), altError, climbRateMps, tickCount);
        double turnRateDegPerTick = RVP_LoiterConfig.LOITER_DIRECTION
                * (hSpeedMps / 20.0) / Math.max(1.0, radius) * (180.0 / Math.PI);
        FixedWingAnalogControls analog = computeFixedWingAnalogControls(headingError, targetBank,
                Mth.wrapDegrees(uavZRot), altError, climbRateMps, turnRateDegPerTick,
                pitchRateDegPerInput, yawRateDegPerInput);
        boolean outOfCapture = Math.abs(radialError) > Math.max(35.0, radius * 0.45);
        LoiterPhase nextPhase = enterRecovery ? LoiterPhase.RECOVERY
                : outOfCapture ? LoiterPhase.APPROACH : LoiterPhase.LOITER;
        return new GuidanceOutput(forward, backward, controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) tangentYaw, false, analog.pitch(), analog.yaw(), analog.roll(),
                nextPhase);
    }

    /** 固定翼低能量恢复：翼平、恢复空速/高度，满足滞回阈值后再捕获盘旋。 */
    public static GuidanceOutput computeRecovery(double uavX, double uavY, double uavZ, float uavYaw,
                                                  double centerX, double centerY, double centerZ,
                                                  double radius, double targetAltitude, double climbRateMps,
                                                  int direction, int tickCount, float currentBankDeg,
                                                  double hSpeedMps, double solutionBank, double solutionSpeed) {
        double dx = uavX - centerX;
        double dz = uavZ - centerZ;
        double tangent = tangentYaw(dx, dz, direction);
        float headingError = Mth.wrapDegrees((float) (tangent - uavYaw));
        double altitudeError = targetAltitude - uavY;
        boolean backward = hSpeedMps > solutionSpeed * SPEED_OVER_FACTOR && pulseThrottle(tickCount, 4);
        boolean forward = !backward && (hSpeedMps < solutionSpeed * 0.72
                || (hSpeedMps < solutionSpeed * 0.96 && pulseThrottle(tickCount, 5)));
        FixedWingControls discrete = computeFixedWingControls(headingError, 0.0F, currentBankDeg,
                altitudeError, climbRateMps, tickCount);
        FixedWingAnalogControls analog = computeFixedWingAnalogControls(headingError, 0.0F,
                currentBankDeg, altitudeError, climbRateMps, 0.0, 0.0, 0.0);
        boolean recovered = hSpeedMps >= solutionSpeed * 0.96
                && altitudeError >= -8.0 && altitudeError <= 12.0
                && Math.abs(currentBankDeg) <= RVP_LoiterConfig.LOITER_BANK - 5.0
                && climbRateMps > -1.0;
        LoiterPhase next = recovered ? LoiterPhase.APPROACH : LoiterPhase.RECOVERY;
        return new GuidanceOutput(forward, backward, discrete.up(), discrete.down(),
                discrete.rollLeft(), discrete.rollRight(), discrete.leftYaw(), discrete.rightYaw(),
                (float) tangent, false, analog.pitch(), analog.yaw(), analog.roll(), next);
    }

    /** 角度线性插值（考虑 wrap）。 */
    private static double lerpAngle(double a, double b, double t) {
        float diff = Mth.wrapDegrees((float) (b - a));
        return a + diff * t;
    }
}
