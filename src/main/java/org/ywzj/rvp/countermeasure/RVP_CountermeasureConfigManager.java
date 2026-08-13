package org.ywzj.rvp.countermeasure;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.HashMap;
import java.util.Map;

/**
 * 载具干扰物配置加载（载具 JSON 顶层 {@code countermeasure} 块，路径 {@code data/<ns>/vehicles/}）。
 *
 * <p>双端安全：服务端 AddReloadListenerEvent 扫描填充；客户端在专用服务器下不触发该事件，
 * 由 {@link #applyFromJsonMap} 复用同一解析逻辑填充（服务端 S2C 同步配置包）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CountermeasureConfigManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_CountermeasureConfigManager INSTANCE = new RVP_CountermeasureConfigManager();

    private static final Logger LOGGER = LogUtils.getLogger();

    private Map<ResourceLocation, RVP_CountermeasureData> configs = Map.of();

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        if (map == null || map.isEmpty()) {
            return;
        }
        Map<ResourceLocation, RVP_CountermeasureData> loaded = new HashMap<>();
        map.forEach((vehicleId, json) -> {
            RVP_CountermeasureData data = RVP_CountermeasureData.parse(json);
            if (data != null) {
                loaded.put(vehicleId, data);
            }
        });
        configs = Map.copyOf(loaded);
        if (!loaded.isEmpty()) {
            LOGGER.info("[RVP-CM] 已加载 {} 台载具的干扰物配置: {}", loaded.size(), loaded.keySet());
        }
    }

    /** 客户端在收到服务端同步配置包后填充（复用同一解析逻辑）。 */
    public void applyFromJsonMap(Map<ResourceLocation, JsonElement> jsonMap) {
        apply(jsonMap, null, null);
    }

    @Nullable
    public RVP_CountermeasureData resolve(@Nullable ResourceLocation vehicleId) {
        return vehicleId == null ? null : configs.get(vehicleId);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
