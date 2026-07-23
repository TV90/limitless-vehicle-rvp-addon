package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterPhase;

/**
 * 无人机盘旋制导算法。参考 SBW 两阶段航向，适配 ywzj_vehicle ControlUnit 模型。
 * <p>非 Mixin 工具类，遵循 rvp-avoid-mixin 原则。</p>
 */
public final class RVP_UavLoiterGuidance {

    private RVP_UavLoiterGuidance() {}

    /** 制导输出，描述要写入 ControlUnit 的字段值。 */
    public record GuidanceOutput(
            boolean forward,
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
     *
     * @param horizontalSpeedBlocksPerTick 水平速度（格/tick）
     * @param maxBankDegrees 最大坡度（度）
     * @return 最小转弯半径（格），硬下限 80
     */
    public static double resolveFixedWingMinRadius(double horizontalSpeedBlocksPerTick, double maxBankDegrees) {
        double speedMs = horizontalSpeedBlocksPerTick * 20.0; // tick → 秒
        double g = 9.8;
        double maxBankRad = Math.toRadians(maxBankDegrees);
        double minR = (speedMs * speedMs) / (g * Math.tan(maxBankRad));
        return Math.max(80.0, minR);
    }

    /**
     * CLIMB 阶段：爬升到安全高度。
     */
    public static GuidanceOutput computeClimb(double uavX, double uavY, double uavZ, float uavYaw,
                                              double centerX, double centerY, double centerZ,
                                              double targetAltitude, double minSafeAltitude,
                                              boolean isRotaryWing) {
        double altError = targetAltitude - uavY;
        boolean needClimb = uavY < minSafeAltitude || altError > 30;

        // 朝圆心方向转向（固定翼需要前进爬升）
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double targetYaw = Math.toDegrees(Math.atan2(dx, -dz));

        if (isRotaryWing) {
            // 旋翼机：原地爬升，不前进
            return new GuidanceOutput(false, true, false, false, false, false, false,
                    uavYaw, true, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
        } else {
            // 固定翼：前进爬升 + 朝圆心转向
            boolean up = altError > 0;
            return new GuidanceOutput(true, up, !up && altError < -5, false, false, false, false,
                    (float) targetYaw, false, needClimb ? LoiterPhase.CLIMB : LoiterPhase.TRANSIT);
        }
    }

    /**
     * TRANSIT 阶段：朝盘旋圆周接入点直飞。
     */
    public static GuidanceOutput computeTransit(double uavX, double uavY, double uavZ, float uavYaw,
                                                double centerX, double centerY, double centerZ,
                                                double radius, double targetAltitude,
                                                boolean isRotaryWing) {
        double dx = centerX - uavX;
        double dz = centerZ - uavZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            dist = 0.001;
        }
        // 圆周接入点 = 圆心方向上距离 = radius 的点
        double approachX = centerX - (dx / dist) * radius;
        double approachZ = centerZ - (dz / dist) * radius;

        // 航向：指向接入点
        double targetYaw = Math.toDegrees(Math.atan2(approachX - uavX, -(approachZ - uavZ)));

        // 高度控制
        double altError = targetAltitude - uavY;
        boolean up = altError > 5;
        boolean down = altError < -5;

        // 阶段切换：接近圆周
        LoiterPhase nextPhase = dist < radius * 1.5 ? LoiterPhase.APPROACH : LoiterPhase.TRANSIT;

        if (isRotaryWing) {
            return new GuidanceOutput(true, up, down, false, false, false, false,
                    (float) targetYaw, true, nextPhase);
        } else {
            float yawError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
            boolean leftYaw = yawError > 3;
            boolean rightYaw = yawError < -3;
            return new GuidanceOutput(true, up, down, false, false, leftYaw, rightYaw,
                    (float) targetYaw, false, nextPhase);
        }
    }

    /**
     * APPROACH 阶段：减速 + 对齐切线。
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

        // 切线航向（左舷朝圆心）
        double tangentYaw = Math.toDegrees(Math.atan2(-dz, -dx));
        // 径向航向（指向圆心）
        double toCenterYaw = Math.toDegrees(Math.atan2(dx, -dz));
        // 混合：远 → 径向，近 → 切线
        double blend = Mth.clamp((float) ((dist - radius) / (radius * 0.5)), 0f, 1f);
        double targetYaw = lerpAngle(tangentYaw, toCenterYaw, blend);

        // 高度控制
        double altError = targetAltitude - uavY;
        boolean up = altError > 5;
        boolean down = altError < -5;

        // 阶段切换：进入圆周 ±15 格
        LoiterPhase nextPhase = Math.abs(dist - radius) < 15 ? LoiterPhase.LOITER : LoiterPhase.APPROACH;

        if (isRotaryWing) {
            // 减速：pulse 调制 forward
            int duty = (int) (4 * blend + 1);
            boolean forward = (tickCount % 5) < duty;
            return new GuidanceOutput(forward, up, down, false, false, false, false,
                    (float) targetYaw, true, nextPhase);
        } else {
            float yawError = Mth.wrapDegrees((float) (targetYaw - uavYaw));
            boolean leftYaw = yawError > 3;
            boolean rightYaw = yawError < -3;
            return new GuidanceOutput(true, up, down, false, false, leftYaw, rightYaw,
                    (float) targetYaw, false, nextPhase);
        }
    }

    /**
     * LOITER 阶段：两阶段制导（切线航向 + 径向航向混合）。
     *
     * @param uavZRot 载具当前 Z 旋转（坡度，度），固定翼用于升力损失补偿
     */
    public static GuidanceOutput computeLoiter(double uavX, double uavY, double uavZ, float uavYaw,
                                               double centerX, double centerY, double centerZ,
                                               double radius, double targetAltitude,
                                               boolean isRotaryWing, int tickCount,
                                               RVP_UavLoiterManager.LoiterState state,
                                               float uavZRot) {
        double dx = uavX - centerX;
        double dz = uavZ - centerZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.001) {
            horizontalDist = 0.001;
        }
        double radialError = horizontalDist - radius;

        // 切线航向（盘旋阶段）：左舷朝向圆心
        double tangentYaw = Math.toDegrees(Math.atan2(-dz, -dx));
        float errorTangent = Mth.wrapDegrees((float) (tangentYaw - uavYaw));

        // 径向航向（拦截阶段）
        double toCenterYaw = Math.toDegrees(Math.atan2(dx, -dz));
        float errorToCenter = Mth.wrapDegrees((float) (toCenterYaw - uavYaw));
        float errorOutward = Mth.wrapDegrees((float) (toCenterYaw + 180.0 - uavYaw));
        float radialYaw = radialError < 0 ? errorOutward : errorToCenter;

        // 混合权重：远 → 径向，近 → 切线
        double distFromOrbit = Math.abs(radialError);
        double blend = Mth.clamp((float) (1.0 - distFromOrbit / radius), 0f, 0.8f);
        double blendZone = 20.0;
        double blendFactor = Mth.clamp((float) ((distFromOrbit - blendZone) / blendZone), 0f, 1f);
        double effectiveRadial = (1.0 - blend) * blendFactor;
        float yawError = (float) (errorTangent * (1.0 - effectiveRadial)
                + radialYaw * effectiveRadial);

        // 径向位置精修
        yawError += Mth.clamp((float) radialError * 0.12f, -35f, 35f);

        // 高度控制
        double altError = targetAltitude - uavY;
        boolean up;
        boolean down;
        if (isRotaryWing) {
            up = altError > 2;
            down = altError < -2;
            // 小误差 pulse 调制防震荡
            if (Math.abs(altError) < 8) {
                int duty = (int) Mth.clamp((float) (Math.abs(altError) / 2), 1f, 4f);
                boolean pulse = (tickCount % 5) < duty;
                up = altError > 2 && pulse;
                down = altError < -2 && pulse;
            }
        } else {
            // 固定翼：坡度升力损失补偿 + 缩小死区 + pulse 调制
            // 升力垂直分量 = L·cos(bank)，坡度越大垂直升力越少，越容易掉高度
            double bankDeg = Math.abs(Mth.wrapDegrees(uavZRot));
            // 坡度损失等效高度补偿：30° 坡度约补偿 0.54 格，45° 约 1.17 格
            double bankLossComp = (1.0 - Math.cos(Math.toRadians(bankDeg))) * 4.0;
            double effectiveAltError = altError + bankLossComp;
            if (effectiveAltError > 1.5) {
                up = true;
                down = false;
            } else if (effectiveAltError < -3.0) {
                up = false;
                down = true;
            } else {
                // 死区内：有坡度时 pulse 抬头补偿升力损失，防止缓慢掉高
                up = bankDeg > 12.0 && (tickCount % 4) < 2;
                down = false;
            }
        }

        // 旋翼机前倾推进；固定翼持续推力维持速度（存能基础）
        boolean forward = isRotaryWing
                ? (radialError > 5 || horizontalDist < radius * 0.5)
                : true;

        if (isRotaryWing) {
            float targetYRot = Mth.wrapDegrees(uavYaw + yawError);
            return new GuidanceOutput(forward, up, down, false, false, false, false,
                    targetYRot, true, LoiterPhase.LOITER);
        } else {
            // 固定翼盘旋：主动滚转建立坡度（主要转弯手段）+ 偏航协调
            // 目标坡度 ~22°，坡度未到时给滚转输入，到目标后靠气动力维持
            double targetBank = 22.0;
            double bankDeg = Math.abs(Mth.wrapDegrees(uavZRot));
            boolean left = yawError > 3 && bankDeg < targetBank;
            boolean right = yawError < -3 && bankDeg < targetBank;
            // 偏航协调：仅大偏差时抵消侧滑
            boolean leftYaw = yawError > 12;
            boolean rightYaw = yawError < -12;
            float targetYRot = Mth.wrapDegrees((float) tangentYaw);
            return new GuidanceOutput(forward, up, down, left, right, leftYaw, rightYaw,
                    targetYRot, false, LoiterPhase.LOITER);
        }
    }

    /** 角度线性插值（考虑 wrap）。 */
    private static double lerpAngle(double a, double b, double t) {
        float diff = Mth.wrapDegrees((float) (b - a));
        return a + diff * t;
    }
}
