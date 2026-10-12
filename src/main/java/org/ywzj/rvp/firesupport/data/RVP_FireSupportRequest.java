package org.ywzj.rvp.firesupport.data;

import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/** 客户端选择的最小请求；所有派生值均由服务端重新计算。 */
public record RVP_FireSupportRequest(
        /** 客户端选择时看到的 profile revision。 */ long revision,
        /** 客户端声称使用的手；服务端会直接读取该手（POD 来源为占位值，不校验）。 */ InteractionHand hand,
        /** 客户端明确选择的 profile 资源 ID；服务端复核其持有规则。 */ ResourceLocation profileId,
        /** profile 内弹药方案 ID。 */ String munitionId,
        /** profile 内打击模式 ID。 */ String fireModeId,
        /** profile 内打击预设 ID。 */ String patternId,
        /** 目标锚点世界 X。 */ double targetX,
        /** 目标锚点世界 Z。 */ double targetZ,
        /** 长轴或徐进方向，单位度。 */ double headingDegrees,
        /** 弹体从发射点飞向目标的独立入场方向，单位度。 */ double inboundHeadingDegrees,
        /** 与 pattern 参数规格精确匹配的动态参数。 */ Map<String, Double> parameters,
        /** 客户端随机幂等键。 */ UUID nonce,
        /** 请求来源（2026-10-13）：TERMINAL=玩家终端 UI 上报；POD=吊舱方块标记服务端自组（跳过终端校验）。 */ Source source) {
    public RVP_FireSupportRequest {
        parameters = Map.copyOf(parameters);
    }

    /** 请求来源；缺省历史行为 = 终端。 */
    public enum Source {
        /** 玩家终端 UI 上报（走手持终端全套校验）。 */ TERMINAL,
        /** 坐标指示吊舱方块标记自动触发（跳过终端校验，改验已保存选择）。 */ POD
    }
}
