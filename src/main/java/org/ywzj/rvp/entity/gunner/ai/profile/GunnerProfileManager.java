package org.ywzj.rvp.entity.gunner.ai.profile;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.behavior.config.RVP_GunnerBehaviorPlan;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 服务端权威 Gunner schema v2 Profile 加载器。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunnerProfileManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation DEFAULT_PROFILE_ID = RVP_MOD.modLocation("default");
    /** 默认 Profile 无攻击、移动或支持行为，只保留管理器生命周期清理。 */
    public static final GunnerProfile DEFAULT_PROFILE = new GunnerProfile(
            "default", RVP_EnumGunnerFaction.FRIENDLY, RVP_GunnerBehaviorPlan.empty());
    public static final GunnerProfileManager INSTANCE = new GunnerProfileManager();

    /** 最近一次成功原子发布的不可变 Profile 快照。 */
    private volatile Map<ResourceLocation, GunnerProfile> profiles = Map.of(DEFAULT_PROFILE_ID, DEFAULT_PROFILE);
    /** 已成功应用的 Profile 资源加载代次，用于触发行为计划退出清理。 */
    private volatile long generation;

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> result = new LinkedHashMap<>();
        // 只扫描当前 gunner/ schema；不再读取 gunner_profiles/ 旧目录或任何旧键别名。
        ResourceScanner.scanDirectory(resourceManager, "gunner", GsonUtil.GSON)
                .forEach((id, json) -> result.put(RVP_MOD.modLocation(id.getPath()), json));
        return Map.copyOf(result);
    }

    @Override
    protected synchronized void apply(Map<ResourceLocation, JsonElement> candidates,
                                      ResourceManager resourceManager, ProfilerFiller profiler) {
        try {
            Map<ResourceLocation, GunnerProfile> compiled = compileSnapshot(candidates);
            profiles = compiled;
            generation++;
            LOGGER.info("已原子加载 {} 个 Gunner schema v2 Profile，generation={}", profiles.size(), generation);
        } catch (RuntimeException exception) {
            // 整批候选失败时保留上一代，避免活动 Gunner 观察到半成品计划。
            LOGGER.error("Gunner Profile schema v2 重载失败，保留 generation={} 的上一代快照: {}",
                    generation, exception.getMessage());
            return;
        }
    }

    /** 先完整编译候选表；任一项失败时不返回半成品 Map。 */
    static Map<ResourceLocation, GunnerProfile> compileSnapshot(Map<ResourceLocation, JsonElement> candidates) {
        Map<ResourceLocation, GunnerProfile> compiled = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : candidates.entrySet()) {
            compiled.put(entry.getKey(), RVP_GunnerProfileCompiler.compile(entry.getKey(), entry.getValue()));
        }
        if (!compiled.containsKey(DEFAULT_PROFILE_ID)) {
            throw new IllegalArgumentException(DEFAULT_PROFILE_ID + ": 缺少必需的默认 Gunner Profile");
        }
        return Map.copyOf(compiled);
    }

    public GunnerProfile getProfile(ResourceLocation id) {
        return profiles.getOrDefault(id, profiles.getOrDefault(DEFAULT_PROFILE_ID, DEFAULT_PROFILE));
    }

    /** 返回当前服务端权威 Profile ID，供数据驱动物品变体同步。 */
    public Set<ResourceLocation> getProfileIds() {
        return profiles.keySet();
    }

    public ResourceLocation normalizeProfileId(String profileName) {
        if (profileName == null || profileName.isBlank() || "default".equals(profileName)) return DEFAULT_PROFILE_ID;
        String normalized = profileName.trim();
        ResourceLocation parsed = normalized.contains(":")
                ? ResourceLocation.tryParse(normalized) : RVP_MOD.modLocation(normalized);
        if (parsed == null) return DEFAULT_PROFILE_ID;
        if (profiles.containsKey(parsed)) return parsed;
        if ("minecraft".equals(parsed.getNamespace())) {
            ResourceLocation remapped = RVP_MOD.modLocation(parsed.getPath());
            if (profiles.containsKey(remapped)) return remapped;
        }
        return DEFAULT_PROFILE_ID;
    }

    /** 返回当前成功发布代次。 */
    public long getGeneration() { return generation; }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
