package org.ywzj.rvp.firesupport.delivery;

import javax.annotation.Nullable;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportBallisticSolver.ActualAirSolution;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportBallisticSolver.AirSolution;
import net.minecraft.world.phys.Vec3;

/** 单发空投航点策略的不可变结果。 */
record RVP_AirstrikeRoutePlanningResult(
        /** 成功时生成的释放计划；失败时为 null。 */ @Nullable Plan plan,
        /** 失败时用于服务端日志的明确原因；成功时为 null。 */ @Nullable String diagnostic) {

    /** 单发空投的确定性生成位置、初速度与预测摘要。 */
    record Plan(
            /** 弹体参考或实时挂架生成位置。 */ Vec3 spawn,
            /** 参考载机速度或实时弹体初始化速度。 */ Vec3 motion,
            /** GPS 单发参考路线要求的入场方向；普通弹道计划为 null。 */ @Nullable Vec3 preferredInboundDirection,
            /** 原始一维空投预测结果；GPS 或实时三维解算时为 null。 */ @Nullable AirSolution solution,
            /** 实际挂架位置下的三维预测结果；参考路线或 GPS 投送时为 null。 */
            @Nullable ActualAirSolution actualSolution,
            /** 建立参考路线时采用的地表以上释放高度；实时规划时为 NaN。 */ double releaseAltitudeMeters) {
    }

    /** 构造成功结果。 */
    static RVP_AirstrikeRoutePlanningResult success(Plan plan) {
        if (plan == null) throw new IllegalArgumentException("空投航线成功结果不能为空");
        return new RVP_AirstrikeRoutePlanningResult(plan, null);
    }

    /** 构造失败结果。 */
    static RVP_AirstrikeRoutePlanningResult failure(String diagnostic) {
        return new RVP_AirstrikeRoutePlanningResult(null,
                diagnostic == null ? "未提供空投航线规划失败详情" : diagnostic);
    }

    /** @return 是否已经生成有效释放计划。 */
    boolean successful() {
        return plan != null;
    }
}
