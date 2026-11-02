package org.ywzj.rvp.flight;

/** 控制算法参数，独立于业务阶段超时；调用者可按组构造或覆盖。 */
public record RVP_FlightTuning(
        /** 航向误差转目标坡度的倍率。 */ double headingToBank,
        /** 坡度误差转滚转杆量的倍率，每度。 */ double bankGain,
        /** 滚转速度阻尼，每度/秒。 */ double bankRateDamping,
        /** 高度差转目标爬升速度，1/秒。 */ double altitudeGain,
        /** 爬升速度误差转俯仰杆量。 */ double climbGain,
        /** 速度误差转油门每刻变化的倍率。 */ double speedGain,
        /** 三轴每刻最大变化。 */ double maximumInputStep,
        /** 油门每刻最大变化，百分点。 */ double maximumThrottleStep,
        /** 有限地形前视，秒。 */ double terrainLookAheadSeconds,
        /** 航向到位容差，度。 */ double headingToleranceDeg,
        /** 高度到位容差，格。 */ double altitudeTolerance,
        /** 速度到位相对容差。 */ double speedToleranceFactor,
        /** 连续到位刻数。 */ int captureTicks,
        /** 退出危险后至少稳飞刻数。 */ int recoveryHoldTicks) {
    public RVP_FlightTuning {
        // 调用公共修正：坏调参只影响本项，用温和巡航默认值补齐，不拒绝业务目标。
        headingToBank = RVP_FlightMath.bounded(headingToBank, 0.6, 0.1, 3);
        bankGain = RVP_FlightMath.bounded(bankGain, 0.035, 0.001, 0.2);
        bankRateDamping = RVP_FlightMath.bounded(bankRateDamping, 0.001, 0, 0.1);
        altitudeGain = RVP_FlightMath.bounded(altitudeGain, 0.08, 0.01, 1);
        climbGain = RVP_FlightMath.bounded(climbGain, 0.16, 0.01, 1);
        speedGain = RVP_FlightMath.bounded(speedGain, 0.3, 0.01, 5);
        maximumInputStep = RVP_FlightMath.bounded(maximumInputStep, 0.12, 0.01, 1);
        maximumThrottleStep = RVP_FlightMath.bounded(maximumThrottleStep, 2, 0.1, 10);
        terrainLookAheadSeconds = RVP_FlightMath.bounded(terrainLookAheadSeconds, 2, 0.5, 4);
        headingToleranceDeg = RVP_FlightMath.bounded(headingToleranceDeg, 12, 1, 45);
        altitudeTolerance = RVP_FlightMath.bounded(altitudeTolerance, 10, 1, 50);
        speedToleranceFactor = RVP_FlightMath.bounded(speedToleranceFactor, 0.15, 0.01, 0.5);
        captureTicks = Math.max(1, Math.min(200, captureTicks));
        recoveryHoldTicks = Math.max(1, Math.min(200, recoveryHoldTicks));
    }
    /** 温和巡航预设，预设只是数值，不驱动业务分支。 */
    public static RVP_FlightTuning smoothCruise() {
        return new RVP_FlightTuning(0.6, 0.035, 0.001, 0.08, 0.16, 0.3,
                0.12, 2, 2, 12, 10, 0.15, 20, 30);
    }
    /** 较快响应预设，仍受公共安全边界限制。 */
    public static RVP_FlightTuning responsiveFlight() {
        return new RVP_FlightTuning(0.9, 0.04, 0.001, 0.12, 0.18, 0.4,
                0.2, 3, 2.5, 10, 10, 0.15, 10, 25);
    }
}
