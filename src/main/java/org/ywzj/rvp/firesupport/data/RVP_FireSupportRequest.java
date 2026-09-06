package org.ywzj.rvp.firesupport.data;

import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/** 客户端选择的最小请求；所有派生值均由服务端重新计算。 */
public record RVP_FireSupportRequest(
        /** 客户端选择时看到的 profile revision。 */ long revision,
        /** 客户端声称使用的手；服务端会直接读取该手。 */ InteractionHand hand,
        /** 客户端明确选择的 profile 资源 ID；服务端复核其持有规则。 */ ResourceLocation profileId,
        /** profile 内弹种 ID。 */ String munitionId,
        /** profile 内射击模式 ID。 */ String fireModeId,
        /** profile 内打击预设 ID。 */ String patternId,
        /** 目标锚点世界 X。 */ double targetX,
        /** 目标锚点世界 Z。 */ double targetZ,
        /** 长轴或徐进方向，单位度。 */ double headingDegrees,
        /** 与 pattern 参数规格精确匹配的动态参数。 */ Map<String, Double> parameters,
        /** 客户端随机幂等键。 */ UUID nonce) {
    public RVP_FireSupportRequest {
        parameters = Map.copyOf(parameters);
    }
}
