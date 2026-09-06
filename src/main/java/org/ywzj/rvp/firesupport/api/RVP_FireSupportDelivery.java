package org.ywzj.rvp.firesupport.api;

import net.minecraft.resources.ResourceLocation;

/** 把一发权威计划落点转换为真实 RVP 弹体的服务端投送接口。 */
public interface RVP_FireSupportDelivery {
    /** @return 注册表中的投送类型 ID。 */
    ResourceLocation typeId();

    /** 调用本项目投送实现；等待或失败必须以明确状态返回。 */
    RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context);
}
