package org.ywzj.rvp.firesupport.delivery;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;

/**
 * 单发空投参考航点的内部规划策略。
 *
 * <p>实现只负责把一发冻结的落点与武器数据转换为释放计划；任务级入场点、出场点、
 * 平滑曲线和时间排程统一由服务端公共航线规划器处理。</p>
 */
interface RVP_AirstrikeRoutePlanningStrategy {
    /**
     * 规划一发弹体的参考或实时释放状态。
     *
     * @param context 冻结的单发投送上下文
     * @param data 已严格解析的空投配置
     * @param sourceMotion 控制器提供的实时载机速度；建立参考航线时为 null
     * @return 成功计划或带明确原因的失败结果
     */
    RVP_AirstrikeRoutePlanningResult plan(
            RVP_FireSupportDeliveryContext context,
            RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data,
            Vec3 sourceMotion);
}
