package org.ywzj.rvp.client.resource.pylon;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** 挂架 display 缓存；资源重载时整体替换，供渲染热路径按模型 id 查找。 */
public final class RVP_PylonDisplayCache {

    private static volatile Map<ResourceLocation, RVP_PylonDisplay> displays = Map.of();

    private RVP_PylonDisplayCache() {}

    public static void replace(Map<ResourceLocation, RVP_PylonDisplay> parsed) {
        displays = Map.copyOf(parsed);
    }

    /** 先按 display id 命中，再按 {@code model} 路径兜底（兼容文件名与 model 不一致的旧写法）。 */
    @Nullable
    public static RVP_PylonDisplay find(ResourceLocation modelId) {
        RVP_PylonDisplay exact = displays.get(modelId);
        if (exact != null) {
            return exact;
        }
        for (RVP_PylonDisplay display : displays.values()) {
            if (modelId.equals(display.model())) {
                return display;
            }
        }
        return null;
    }
}
