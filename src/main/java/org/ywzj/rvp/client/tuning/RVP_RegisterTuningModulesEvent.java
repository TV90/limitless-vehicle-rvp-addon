package org.ywzj.rvp.client.tuning;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

/** 客户端初始化时在MOD加载总线上发布；游戏总线此时尚未启动，不能用于登记模块。 */
public final class RVP_RegisterTuningModulesEvent extends Event implements IModBusEvent {
    /** 本次客户端启动正在构建的唯一目录，事件返回后锁定。 */
    private final RVP_TuningRegistry registry;

    public RVP_RegisterTuningModulesEvent(RVP_TuningRegistry registry) { this.registry = registry; }

    /** 调用通用目录登记模块，重复稳定键会明确报错。 */
    public void register(RVP_TuningModule module) { registry.register(module); }
}
