package org.ywzj.rvp.client.resource;

import com.mojang.logging.LogUtils;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 识别框始终使用英文包中的完整名称，切换游戏语言无需改变。 */
public final class RVP_OpticalVehicleNames extends SimplePreparableReloadListener<Map<String, String>> {
    public static final RVP_OpticalVehicleNames INSTANCE = new RVP_OpticalVehicleNames();
    /** 按资源包优先级构建的英文名字快照。 */
    private Map<String, String> names = Map.of();
    private RVP_OpticalVehicleNames() {}

    @Override
    protected Map<String, String> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<String, String> english = new HashMap<>();
        // 在资源重载时一次性读取原版和载具包英文文件，避免每帧访问磁盘。
        resources.listResourceStacks("lang", id -> id.getPath().equals("lang/en_us.json"))
                .forEach((id, stack) -> {
                    for (var resource : stack) {
                        try (var input = resource.open()) {
                            Language.loadFromJson(input, (key, text) -> {
                                if (key.startsWith("entity.") || key.startsWith("ui.rvp.optical.name.")) {
                                    english.put(key, text);
                                }
                            });
                        } catch (IOException | com.google.gson.JsonParseException e) {
                            LogUtils.getLogger().warn("Could not read English vehicle names from {}", id, e);
                        }
                    }
                });
        return Map.copyOf(english);
    }

    @Override
    protected void apply(Map<String, String> names, ResourceManager resources, ProfilerFiller profiler) {
        this.names = names;
    }

    public static String name(AbstractVehicle vehicle) {
        var id = vehicle.getVehicleId();
        // 本体客户端实体可能只有通用载具类型名，优先查具体车型的翻译键。
        String key = "entity." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        var english = INSTANCE.names;
        String named = english.getOrDefault("ui.rvp.optical.name." + key, english.get(key));
        if (named != null) return named;
        var name = vehicle.getName();
        if (name != null && name.getContents() instanceof TranslatableContents translated) {
            String label = english.get(translated.getKey());
            if (label != null && !translated.getKey().startsWith("entity.ywzj_vehicle.")) return label;
        }
        return id.getPath().replace('_', ' ').toUpperCase(Locale.ROOT);
    }
}
