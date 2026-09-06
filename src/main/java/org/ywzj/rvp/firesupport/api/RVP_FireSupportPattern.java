package org.ywzj.rvp.firesupport.api;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportImpactPoint;

/** 按权威随机种子和轮次解析单发计划落点的纯数学接口。 */
public interface RVP_FireSupportPattern {
    /** @return 注册表中的几何类型 ID。 */
    ResourceLocation typeId();

    /** 调用本项目几何实现，为本轮生成确定性 XZ 落点。 */
    RVP_FireSupportImpactPoint resolve(Context context);

    /** 几何计算所需的不可变输入。 */
    record Context(
            /** 落区锚点 X。 */ double anchorX,
            /** 落区锚点 Z。 */ double anchorZ,
            /** 长轴/前进方向，单位度；0 指向 +Z。 */ double headingDegrees,
            /** 从 0 开始的任务轮次。 */ int roundIndex,
            /** 任务总弹数。 */ int totalRounds,
            /** 服务端权威随机种子。 */ long seed,
            /** 规范化后的动态参数。 */ Map<String, Double> parameters,
            /** 射击模式散布倍率。 */ double dispersionMultiplier) {
        public Context { parameters = Map.copyOf(parameters); }
    }
}
