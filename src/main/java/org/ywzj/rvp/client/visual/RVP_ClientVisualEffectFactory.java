package org.ywzj.rvp.client.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Optional;

/** 客户端视觉实例工厂；每种效果类型独立解析自己的类型化预设。 */
@FunctionalInterface
public interface RVP_ClientVisualEffectFactory {
    /** 根据当前客户端世界和不可变领域事件创建视觉实例。 */
    Optional<RVP_ClientVisualEffect> create(ClientLevel level, RVP_VisualEffectEvent event);
}
