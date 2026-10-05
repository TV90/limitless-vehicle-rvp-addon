package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterPhase;

/**
 * 无人机盘旋制导算法。
 * <p><b>固定翼布尔杆量闭环（2026-10-06）</b>：本体 FixedWingVehicle 在无人驾驶时每 tick
 * 把 {@code controlUnit.yRot/xRot} 强制重置为当前姿态，旧版"写 yRot 目标航向 + 本体自动改平
 * 滚转"的转弯通道在无人场景被整体短路。改为纯布尔杆量闭环：leftYaw/rightYaw（偏航角速度）+
 * left/right（滚转 bang-bang 脉冲控坡度）+ up/down/forward/backward（高度/速度/油门脉冲），
 * 无人驾驶全部有效，零 Mixin。旋翼机沿用模拟偏航，零改动。</p>
 * <p><b>可持续盘旋解算（resolveFixedWingLoiterSolution）</b>：固定翼升力 = 阻力×升阻比随速度²
 * 缩放，配置半径在速度过快/升力不足时物理上守不住高度。解算器按气动参数推出最小平飞速度
 * v_min，与配置半径联立解出满足升力约束的（实际半径 R，临界坡度 φ=asin(v_min²/gR) 封顶，
 * 目标速度 v=sqrt(Rg·tanφ)）；所需坡度超上限时按上限反算最小半径兜底。盘旋中节流阀闭环
 * 把空速压到目标速度——半径/坡度/速度三者自洽，任意气动调参下自动匹配。</p>
 * <p><b>高度垂直速度闭环</b>：目标爬升率 = clamp(高度误差×0.08, ±4 m/s)，再按（目标爬升率 −
 * 实际垂直速度）误差脉冲 up/down——垂直速度即时阻尼，消除纯高度误差脉冲的过冲振荡。</p>
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
    private static final double SPEED_UNDER_FACTOR = 0.7;

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
            LoiterPhase nextPhase
    ) {}

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
     * @param headingErrorDeg 航向误差（wrap 后）：期望 − 当前，正值 = 需向左转
     * @param targetBankDeg   目标坡度（带符号）：负 = 左坡度（左转）
     * @param currentBankDeg  当前滚转角（uav.getZRot()，带符号）：负 = 左坡度
     * @param altError        高度误差（目标 − 当前，正 = 需爬升，格）
     * @param climbRateMps    实际垂直速度（m/s，deltaMovement.y × 20）
     * @param tickCount       全局 tick（脉冲相位）
     */
    public static FixedWingControls computeFixedWingControls(float headingErrorDeg, float targetBankDeg,
                                                             float currentBankDeg, double altError,
                                                             double climbRateMps, int tickCount) {
        boolean leftYaw = headingErrorDeg > YAW_BOOLEAN_THRESHOLD;
        boolean rightYaw = headingErrorDeg < -YAW_BOOLEAN_THRESHOLD;
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
                                              boolean isRotaryWing, int tickCount) {
        double altError = targetAltitude - uavY;
        boolean needClimb = uavY < targetAltitude - 20 || altError > 20;

        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double targetYaw = Math.toDegrees(Math.atan2(dx, -dz));

        if (isRotaryWing) {
            return new GuidanceOutput(false, false, true, false, false, false, false, false,
                    uavYaw, true, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
        }
        float headingError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
        float targetBank = Mth.clamp(headingError * BANK_PROPORTIONAL_GAIN,
                -(float) RVP_LoiterConfig.ALIGN_BANK_CAP, (float) RVP_LoiterConfig.ALIGN_BANK_CAP);
        FixedWingControls controls = computeFixedWingControls(headingError, targetBank, 0f,
                altError, climbRateMps, tickCount);
        boolean[] throttle = fixedWingThrottle(altError, hSpeedMps, solutionSpeedMps, tickCount);
        return new GuidanceOutput(throttle[0], throttle[1], controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) targetYaw, false, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
    }

    /**
     * TRANSIT 阶段：朝盘旋圆周接入点直飞。
     */
    public static GuidanceOutput computeTransit(double uavX, double uavY, double uavZ, float uavYaw,
                                                double centerX, double centerY, double centerZ,
                                                double radius, double targetAltitude, double climbRateMps,
                                                double hSpeedMps, double solutionSpeedMps,
                                                boolean isRotaryWing, int tickCount) {
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            dist = 0.001;
        }
        double approachX = centerX - (dx / dist) * radius;
        double approachZ = centerZ - (dz / dist) * radius;
        double targetYaw = Math.toDegrees(Math.atan2(approachX - uavX, -(approachZ - uavZ)));

        double altError = targetAltitude - uavY;
        LoiterPhase nextPhase = dist < radius * 1.5 ? LoiterPhase.APPROACH : LoiterPhase.TRANSIT;

        if (isRotaryWing) {
            boolean up = altError > 5;
            boolean down = altError < -5;
            return new GuidanceOutput(true, false, up, down, false, false, false, false,
                    (float) targetYaw, true, nextPhase);
        }
        float headingError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
        float targetBank = Mth.clamp(headingError * BANK_PROPORTIONAL_GAIN,
                -(float) RVP_LoiterConfig.ALIGN_BANK_CAP, (float) RVP_LoiterConfig.ALIGN_BANK_CAP);
        FixedWingControls controls = computeFixedWingControls(headingError, targetBank, 0f,
                altError, climbRateMps, tickCount);
        boolean[] throttle = fixedWingThrottle(altError, hSpeedMps, solutionSpeedMps, tickCount);
        return new GuidanceOutput(throttle[0], throttle[1], controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) targetYaw, false, nextPhase);
    }

    /**
     * APPROACH 阶段：对齐切线，准备进入盘旋。
     */
    public static GuidanceOutput computeApproach(double uavX, double uavY, double uavZ, float uavYaw,
                                                 double centerX, double centerY, double centerZ,
                                                 double radius, double targetAltitude, double climbRateMps,
                                                 int loiterDirection, boolean isRotaryWing, int tickCount) {
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            dist = 0.001;
        }

        double tangent = tangentYaw(uavX - centerX, uavZ - centerZ, loiterDirection);
        double toCenterYaw = Math.toDegrees(Math.atan2(dx, -dz));
        double blend = Mth.clamp((float) ((dist - radius) / (radius * 0.5)), 0f, 1f);
        double targetYaw = lerpAngle(tangent, toCenterYaw, blend);

        double altError = targetAltitude - uavY;
        LoiterPhase nextPhase = Math.abs(dist - radius) < 25 ? LoiterPhase.LOITER : LoiterPhase.APPROACH;

        if (isRotaryWing) {
            boolean up = altError > 3;
            boolean down = altError < -3;
            int duty = (int) (4 * blend + 1);
            boolean forward = (tickCount % 5) < duty;
            return new GuidanceOutput(forward, false, up, down, false, false, false, false,
                    (float) targetYaw, true, nextPhase);
        }
        float headingError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
        float targetBank = Mth.clamp(headingError * BANK_PROPORTIONAL_GAIN,
                -(float) RVP_LoiterConfig.ALIGN_BANK_CAP, (float) RVP_LoiterConfig.ALIGN_BANK_CAP);
        FixedWingControls controls = computeFixedWingControls(headingError, targetBank, 0f,
                altError, climbRateMps, tickCount);
        return new GuidanceOutput(true, false, controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) targetYaw, false, nextPhase);
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
                    targetYRot, true, LoiterPhase.LOITER);
        }

        // 固定翼盘旋：目标坡度 = 方向 ×（解算坡度 + 径向修正，外侧收紧）
        float radialCorrection = Mth.clamp((float) radialError * 0.2f, -RADIAL_CORRECTION_CAP, RADIAL_CORRECTION_CAP);
        float targetBank = Mth.clamp(
                RVP_LoiterConfig.LOITER_DIRECTION * ((float) solutionBank + radialCorrection),
                -45f, 45f);
        float headingError = Mth.wrapDegrees((float) (tangentYaw - uavYaw));

        // 节流阀双向闭环：超解算速度收油（速度是半径/坡度/升力自洽的前提）；
        // 欠速或高度不足给油。收油优先级（占空）高于给油，保证速度上限刚性。
        boolean backward = hSpeedMps > solutionSpeed * SPEED_OVER_FACTOR && pulseThrottle(tickCount, 4);
        boolean forward = (altError > 5 || hSpeedMps < solutionSpeed * SPEED_UNDER_FACTOR)
                && pulseThrottle(tickCount, 2);

        FixedWingControls controls = computeFixedWingControls(headingError, targetBank,
                Mth.wrapDegrees(uavZRot), altError, climbRateMps, tickCount);
        return new GuidanceOutput(forward, backward, controls.up(), controls.down(),
                controls.rollLeft(), controls.rollRight(),
                controls.leftYaw(), controls.rightYaw(),
                (float) tangentYaw, false, LoiterPhase.LOITER);
    }

    /** 角度线性插值（考虑 wrap）。 */
    private static double lerpAngle(double a, double b, double t) {
        float diff = Mth.wrapDegrees((float) (b - a));
        return a + diff * t;
    }
}
