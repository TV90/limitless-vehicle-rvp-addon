package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterPhase;

/**
 * 无人机盘旋制导算法。
 * <p>核心设计：固定翼不直接按 left/right（累加式，持续按下会导致滚转输入失控）。
 * 通过设置 controlUnit.yRot 为目标航向 + 偏移角，利用本体 FixedWingVehicle 的自动改平逻辑
 * （偏差>5°时朝目标方向滚转）产生适度滚转来实现转弯。偏航由自动改平逻辑协调。</p>
 * <p>节流阀自动调节：高度过高时脉冲式 backward 减油门，过低时脉冲式 forward 加油门。</p>
 * <p>非 Mixin 工具类，遵循 rvp-avoid-mixin 原则。</p>
 */
public final class RVP_UavLoiterGuidance {

    private RVP_UavLoiterGuidance() {}

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

    /**
     * 计算固定翼最小转弯半径。R = V² / (g·tan(bank))。
     */
    public static double resolveFixedWingMinRadius(double horizontalSpeedBlocksPerTick, double maxBankDegrees) {
        double speedMs = horizontalSpeedBlocksPerTick * 20.0;
        double g = 9.8;
        double maxBankRad = Math.toRadians(maxBankDegrees);
        double minR = (speedMs * speedMs) / (g * Math.tan(maxBankRad));
        return Math.max(80.0, minR);
    }

    /**
     * 固定翼脉冲式高度控制。
     * <p>本体飞控的 up/down 是累加式的，持续按下会导致俯仰输入不断增大直到后空翻。
     * 改为脉冲式：每 10 tick 周期中只按 N tick，其余 tick 不按让自动改平逻辑恢复俯仰。</p>
     */
    private static boolean pulseAltitudeControl(double altError, int tickCount) {
        double absErr = Math.abs(altError);
        if (absErr < 3) {
            return false;
        }
        int period = 10;
        int duty;
        if (absErr > 20) {
            duty = 3;
        } else if (absErr > 10) {
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

    // ===== 固定翼核心策略 =====
    // 不按 left/right/leftYaw/rightYaw，只设置 controlUnit.yRot
    // 本体 FixedWingVehicle 在 left/right 均未按下时，自动改平逻辑会根据 controlUnit.yRot
    // 与当前航向的偏差产生滚转（偏差>5°时朝目标方向滚转，偏差≤5°时滚转回正）
    // 通过设置 yRot = 切线方向 + 偏移角(由盘旋坡度和方向决定)，让自动改平逻辑产生滚转

    /**
     * CLIMB 阶段：爬升到安全高度。
     */
    public static GuidanceOutput computeClimb(double uavX, double uavY, double uavZ, float uavYaw,
                                              double centerX, double centerY, double centerZ,
                                              double targetAltitude, double minSafeAltitude,
                                              boolean isRotaryWing, int tickCount) {
        double altError = targetAltitude - uavY;
        boolean needClimb = uavY < minSafeAltitude || altError > 20;

        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double targetYaw = Math.toDegrees(Math.atan2(dx, -dz));

        if (isRotaryWing) {
            return new GuidanceOutput(false, false, true, false, false, false, false, false,
                    uavYaw, true, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
        } else {
            boolean up = altError > 3 && pulseAltitudeControl(altError, tickCount);
            boolean down = altError < -3 && pulseAltitudeControl(altError, tickCount);
            return new GuidanceOutput(true, false, up, down, false, false, false, false,
                    (float) targetYaw, false, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
        }
    }

    /**
     * TRANSIT 阶段：朝盘旋圆周接入点直飞。
     */
    public static GuidanceOutput computeTransit(double uavX, double uavY, double uavZ, float uavYaw,
                                                double centerX, double centerY, double centerZ,
                                                double radius, double targetAltitude,
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
        } else {
            boolean up = altError > 3 && pulseAltitudeControl(altError, tickCount);
            boolean down = altError < -3 && pulseAltitudeControl(altError, tickCount);
            return new GuidanceOutput(true, false, up, down, false, false, false, false,
                    (float) targetYaw, false, nextPhase);
        }
    }

    /**
     * APPROACH 阶段：对齐切线，准备进入盘旋。
     */
    public static GuidanceOutput computeApproach(double uavX, double uavY, double uavZ, float uavYaw,
                                                 double centerX, double centerY, double centerZ,
                                                 double radius, double targetAltitude,
                                                 boolean isRotaryWing, int tickCount) {
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            dist = 0.001;
        }

        double tangentYaw = Math.toDegrees(Math.atan2(-dz, -dx));
        double toCenterYaw = Math.toDegrees(Math.atan2(dx, -dz));
        double blend = Mth.clamp((float) ((dist - radius) / (radius * 0.5)), 0f, 1f);
        double targetYaw = lerpAngle(tangentYaw, toCenterYaw, blend);

        double altError = targetAltitude - uavY;
        boolean up = altError > 3 && pulseAltitudeControl(altError, tickCount);
        boolean down = altError < -3 && pulseAltitudeControl(altError, tickCount);

        LoiterPhase nextPhase = Math.abs(dist - radius) < 25 ? LoiterPhase.LOITER : LoiterPhase.APPROACH;

        if (isRotaryWing) {
            int duty = (int) (4 * blend + 1);
            boolean forward = (tickCount % 5) < duty;
            return new GuidanceOutput(forward, false, up, down, false, false, false, false,
                    (float) targetYaw, true, nextPhase);
        } else {
            return new GuidanceOutput(true, false, up, down, false, false, false, false,
                    (float) targetYaw, false, nextPhase);
        }
    }

    /**
     * LOITER 阶段：盘旋。
     * <p>固定翼核心策略：
     * 1. 设置 controlUnit.yRot = 切线方向 + 偏移角(由 loiterBank 和 loiterDirection 决定)
     * 2. 节流阀自动调节：高度过高减油门，过低加油门
     * 3. 不按 left/right/leftYaw/rightYaw，让自动改平逻辑协调滚转和偏航</p>
     *
     * @param loiterBank 目标坡度（度），从 JSON rvp_loiter_bank 读取
     * @param loiterDirection 盘旋方向：1=右盘旋, -1=左盘旋，从 JSON rvp_loiter_direction 读取
     * @param uavZRot 载具当前 Z 旋转（坡度），用于升力损失补偿
     */
    public static GuidanceOutput computeLoiter(double uavX, double uavY, double uavZ, float uavYaw,
                                               double centerX, double centerY, double centerZ,
                                               double radius, double targetAltitude,
                                               boolean isRotaryWing, int tickCount,
                                               RVP_UavLoiterManager.LoiterState state,
                                               float uavZRot,
                                               double loiterBank, int loiterDirection) {
        double dx = uavX - centerX;
        double dz = uavZ - centerZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.001) {
            horizontalDist = 0.001;
        }
        double radialError = horizontalDist - radius;

        // 切线航向（顺时针盘旋方向）
        double tangentYaw = Math.toDegrees(Math.atan2(-dz, -dx));

        // 高度控制
        double altError = targetAltitude - uavY;
        boolean up;
        boolean down;
        if (isRotaryWing) {
            up = altError > 2;
            down = altError < -2;
            if (Math.abs(altError) < 8) {
                int duty = (int) Mth.clamp((float) (Math.abs(altError) / 2), 1f, 4f);
                boolean pulse = (tickCount % 5) < duty;
                up = altError > 2 && pulse;
                down = altError < -2 && pulse;
            }
        } else {
            double bankDeg = Math.abs(Mth.wrapDegrees(uavZRot));
            double bankLossComp = (1.0 - Math.cos(Math.toRadians(bankDeg))) * 12.0;
            double effectiveAltError = altError + bankLossComp;
            up = effectiveAltError > 3 && pulseAltitudeControl(effectiveAltError, tickCount);
            down = effectiveAltError < -3 && pulseAltitudeControl(effectiveAltError, tickCount);
        }

        if (isRotaryWing) {
            boolean forward = (radialError > 5 || horizontalDist < radius * 0.5);
            double toCenterYaw = Math.toDegrees(Math.atan2(centerX - uavX, -(centerZ - uavZ)));
            float yawError = Mth.wrapDegrees((float) (tangentYaw - uavYaw));
            yawError += Mth.clamp((float) radialError * 0.12f, -35f, 35f);
            float targetYRot = Mth.wrapDegrees(uavYaw + yawError);
            return new GuidanceOutput(forward, false, up, down, false, false, false, false,
                    targetYRot, true, LoiterPhase.LOITER);
        } else {
            // 固定翼盘旋核心逻辑：
            // offset = loiterBank * loiterDirection
            // 右盘旋(direction=1): offset > 0 → 目标航向在切线右侧 → 右滚转
            // 左盘旋(direction=-1): offset < 0 → 目标航向在切线左侧 → 左滚转
            float radialCorrection = Mth.clamp((float) (radialError * 0.2f), -10f, 10f);
            float offset = (float) (loiterBank * loiterDirection) + radialCorrection * loiterDirection;
            float targetYRot = Mth.wrapDegrees((float) (tangentYaw + offset));

            // 节流阀自动调节：根据高度误差脉冲式 forward/backward
            // 高度太高(altError < -5)：减油门
            // 高度太低(altError > 5)：加油门
            // 高度适中：不调节
            boolean forward = altError > 5 && pulseThrottle(tickCount, 2);
            boolean backward = altError < -5 && pulseThrottle(tickCount, 2);

            return new GuidanceOutput(forward, backward, up, down, false, false, false, false,
                    targetYRot, false, LoiterPhase.LOITER);
        }
    }

    /** 角度线性插值（考虑 wrap）。 */
    private static double lerpAngle(double a, double b, double t) {
        float diff = Mth.wrapDegrees((float) (b - a));
        return a + diff * t;
    }
}
