package org.ywzj.rvp.weapon.visual.api;

/** 公共视觉事件消费端口；网络消息无需引用客户端实现。 */
@FunctionalInterface
public interface RVP_VisualEffectSink {
    /** 在接收侧主线程消费一次不可变视觉事件。 */
    void accept(RVP_VisualEffectEvent event);
}
