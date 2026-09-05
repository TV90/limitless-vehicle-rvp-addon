package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/** 投送类型的阶段 A 配置解析与武器能力检查接口；实体投送在阶段 B 实现。 */
public interface RVP_FireSupportDeliveryFactory {
    /** @return 投送类型 ID。 */
    ResourceLocation typeId();

    /** 严格解析类型专属数据为不可变对象。 */
    Object parse(JsonObject data, RVP_FireSupportProblemCollector problems, String path);

    /** 使用本体实际武器索引解析出的 RVP 数据检查投送兼容性。 */
    void validateWeapon(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                        RVP_FireSupportProblemCollector problems, String path);
}
