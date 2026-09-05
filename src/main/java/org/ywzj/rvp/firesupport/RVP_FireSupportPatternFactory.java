package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/** 严格解析类型专属 JSON 并创建不可变几何实现。 */
public interface RVP_FireSupportPatternFactory {
    /** @return 工厂类型 ID。 */
    ResourceLocation typeId();

    /** 调用本项目严格解析器，校验并创建几何实现。 */
    RVP_FireSupportPattern parseAndCreate(JsonObject data, RVP_FireSupportProblemCollector problems, String path);
}
