package org.ywzj.rvp.client.resource.pylon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.ywzj.rvp.client.render.RVP_CustomMountRenderLogic;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.HashMap;
import java.util.Map;

/**
 * 扫描 {@code assets/rvp/display/pylon} 并整体替换挂架 display 缓存。
 *
 * <p>本目录由 RVP 自行管理，不走本体的 DisplayManager，因此挂架模型不会被注册成饰品；
 * 未配置条目的挂架仍由渲染侧回退到 decoration / weapon / 直读模型。</p>
 */
public final class RVP_PylonDisplayReloadListener
        extends SimplePreparableReloadListener<Map<ResourceLocation, RVP_PylonDisplay>> {

    public static final RVP_PylonDisplayReloadListener INSTANCE = new RVP_PylonDisplayReloadListener();

    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_PylonDisplayReloadListener() {}

    @Override
    protected @NotNull Map<ResourceLocation, RVP_PylonDisplay> prepare(@NotNull ResourceManager resourceManager,
                                                                      @NotNull ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> resources = ResourceScanner.scanDirectory(resourceManager,
                FileToIdConverter.json("display/pylon"), GsonUtil.GSON);
        Map<ResourceLocation, RVP_PylonDisplay> parsed = new HashMap<>(resources.size());
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = GsonHelper.convertToJsonObject(entry.getValue(), "rvp pylon display");
                RVP_PylonDisplay display = RVP_PylonDisplay.parse(entry.getKey(), obj);
                if (display == null) {
                    LOGGER.warn("[RVP] 挂架 display 缺少合法 model，已跳过: {}", entry.getKey());
                    continue;
                }
                parsed.put(entry.getKey(), display);
            } catch (Exception exception) {
                LOGGER.warn("[RVP] 挂架 display 解析失败: {}", entry.getKey(), exception);
            }
        }
        return parsed;
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, RVP_PylonDisplay> parsed,
                         @NotNull ResourceManager resourceManager,
                         @NotNull ProfilerFiller profiler) {
        RVP_PylonDisplayCache.replace(parsed);
        // display 内容变化会让旧模型缓存失真，清空后由下一次渲染按新缓存重建。
        RVP_CustomMountRenderLogic.clearModelCache();
    }
}
