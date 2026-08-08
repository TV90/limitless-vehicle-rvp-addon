package org.ywzj.rvp.client.visual;

import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 客户端视觉实例工厂；阶段 B 将注册温压实现。 */
@FunctionalInterface
public interface RVP_ClientVisualEffectFactory {
    /** 根据不可变事件创建客户端效果实例。 */
    void create(RVP_VisualEffectEvent event);
}
