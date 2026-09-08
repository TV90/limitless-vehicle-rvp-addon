package org.ywzj.rvp.firesupport.api;

import net.minecraft.resources.ResourceLocation;

/** 把一发权威计划落点转换为真实 RVP 弹体的服务端投送接口。 */
public interface RVP_FireSupportDelivery {
    /** @return 注册表中的投送类型 ID。 */
    ResourceLocation typeId();

    /** @return 计划发射前允许准备生成点 Chunk 的 Tick 数。 */
    int preloadTicks();

    /**
     * 提前准备本发所需的目标/生成点 Chunk；不得生成实体或同步加载区块。
     * 返回 PREPARED 表示当前已具备生成条件，TOO_EARLY/WAITING_FOR_CHUNK 表示继续等待。
     */
    RVP_FireSupportDeliveryResult prepare(RVP_FireSupportDeliveryContext context);

    /** 调用本项目投送实现；等待或失败必须以明确状态返回。 */
    RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context);
}
