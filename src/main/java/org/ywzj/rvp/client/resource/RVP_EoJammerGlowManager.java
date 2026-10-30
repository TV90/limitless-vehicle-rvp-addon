package org.ywzj.rvp.client.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 光电干扰机玻片夜间发光规则（display JSON 的 {@code eo_jammer_glow}）。
 *
 * <p>每条规则把一根"渲染模型骨"声明为干扰机玻片，并可选绑定一个载具 JSON
 * {@code bone_modules} 键名作为损坏联动：
 * <pre>
 * "eo_jammer_glow": [
 *   { "glass_bone": "jammer_1", "module_bone": "ERA1" }
 * ]
 * </pre>
 * {@code module_bone} 省略时只按"黑暗 + 非残骸"发光，不检查 JAMMER 模块是否失效。</p>
 *
 * <p>本类只负责读取与索引，真正的可见性判定与绘制在
 * {@code RVP_EoJammerGlowRenderer}；规则随客户端资源重载整体替换（F3+T 即生效）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_EoJammerGlowManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_EoJammerGlowManager INSTANCE = new RVP_EoJammerGlowManager();

    /** display JSON 顶层字段名。 */
    public static final String FIELD = "eo_jammer_glow";

    /** 资源重载后整体替换，渲染线程只读快照，不会看到改了一半的规则表。 */
    private volatile Map<ResourceLocation, List<Rule>> rulesByDisplayId = Map.of();

    private RVP_EoJammerGlowManager() {
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        // 调用本体资源扫描器读取全部命名空间下的 display/vehicle 目录，与透明模式管理器同一入口。
        return ResourceScanner.scanDirectory(resourceManager, FileToIdConverter.json("display/vehicle"), GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        Map<ResourceLocation, List<Rule>> loaded = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(entry.getValue(), "rvp eo jammer glow");
            } catch (Exception ignored) {
                continue;
            }
            // 调用纯解析入口，单条非法配置只丢弃自己，不影响其它载具显示。
            List<Rule> rules = parseRules(root);
            if (!rules.isEmpty()) {
                loaded.put(entry.getKey(), rules);
            }
        }
        rulesByDisplayId = Map.copyOf(loaded);
    }

    /** 解析 display JSON 顶层 {@code eo_jammer_glow}；无配置或全部非法时返回空表。 */
    public static List<Rule> parseRules(@Nullable JsonObject root) {
        if (root == null || !root.has(FIELD) || !root.get(FIELD).isJsonArray()) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(FIELD)) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            // 玻片骨为空视为无效条目：避免空骨名在渲染热路径被反复查询。
            String glassBone = GsonHelper.getAsString(object, "glass_bone", "").trim();
            if (glassBone.isEmpty()) {
                continue;
            }
            String moduleBone = GsonHelper.getAsString(object, "module_bone", "").trim();
            rules.add(new Rule(glassBone, moduleBone.isEmpty() ? null : moduleBone));
        }
        return rules.isEmpty() ? List.of() : List.copyOf(rules);
    }

    /** 取该 display 的玻片发光规则；无配置返回空表，渲染热路径零额外分配。 */
    public List<Rule> rulesFor(@Nullable ResourceLocation displayId) {
        return displayId == null ? List.of() : rulesByDisplayId.getOrDefault(displayId, List.of());
    }

    /**
     * 单条玻片规则。
     *
     * @param glassBone  渲染模型骨名（发光/发红的部位）
     * @param moduleBone 载具 JSON {@code bone_modules} 键名，用于查询 JAMMER 模块是否失效；null 表示不联动
     */
    public record Rule(String glassBone, @Nullable String moduleBone) {
    }
}
