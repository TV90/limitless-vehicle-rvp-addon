package org.ywzj.rvp.firesupport.api;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;

/** 投送类型的严格配置解析、武器能力检查与运行时实现工厂。 */
public interface RVP_FireSupportDeliveryFactory {
    /** @return 投送类型 ID。 */
    ResourceLocation typeId();

    /** 严格解析类型专属数据为不可变对象。 */
    Object parse(JsonObject data, RVP_FireSupportProblemCollector problems, String path);

    /** 从已严格解析的类型专属数据创建不可变投送实现。 */
    RVP_FireSupportDelivery create(Object parsedData);

    /** 使用本体实际武器索引解析出的 RVP 数据检查投送兼容性。 */
    void validateWeapon(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                        RVP_FireSupportProblemCollector problems, String path);
}
