package org.ywzj.rvp.weapon.visual.api;

import org.ywzj.rvp.weapon.data.RVP_VisualEffectData;

import java.util.Optional;

/** 把当前 schema 的数据配置解析为不可变视觉事件。 */
public interface RVP_DetonationVisualResolver {
    /** 返回该解析器是否支持给定配置。 */
    boolean supports(RVP_VisualEffectData data);

    /** 校验配置并解析事件；非法或超限配置返回空。 */
    Optional<RVP_VisualEffectEvent> resolve(RVP_DetonationVisualContext context, RVP_VisualEffectData data);
}
