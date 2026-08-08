package org.ywzj.rvp.weapon.visual.api;

import net.minecraft.server.level.ServerLevel;

/** 服务端视觉事件发布端口；业务调用方不直接依赖网络通道。 */
@FunctionalInterface
public interface RVP_VisualEventPublisher {
    /** 把不可变视觉事件发布给指定服务端世界中的目标客户端。 */
    void publish(ServerLevel level, RVP_VisualEffectEvent event);
}
