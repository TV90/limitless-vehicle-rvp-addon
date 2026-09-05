package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 内建投送工厂注册表；阶段 A 负责严格配置与真实武器能力校验。 */
public final class RVP_FireSupportDeliveryTypes {
    /** 首版垂直真实弹体投送 ID。 */ public static final ResourceLocation VERTICAL_PROJECTILE = id("vertical_projectile");
    /** 注册阶段可变工厂表。 */ private static final Map<ResourceLocation, RVP_FireSupportDeliveryFactory> MUTABLE = new LinkedHashMap<>();
    /** 冻结后的不可变工厂表。 */ private static Map<ResourceLocation, RVP_FireSupportDeliveryFactory> factories;

    static {
        register(new VerticalProjectileFactory());
        factories = Map.copyOf(MUTABLE);
    }

    private RVP_FireSupportDeliveryTypes() {}

    private static void register(RVP_FireSupportDeliveryFactory factory) {
        if (factories != null) throw new IllegalStateException("炮火投送注册表已经冻结");
        if (MUTABLE.putIfAbsent(factory.typeId(), factory) != null) throw new IllegalStateException("重复投送类型 " + factory.typeId());
    }

    /** @return 已冻结的工厂；未知类型返回 null。 */
    public static RVP_FireSupportDeliveryFactory get(ResourceLocation id) { return factories.get(id); }

    /** @return 已注册投送类型的不可变视图。 */
    public static Map<ResourceLocation, RVP_FireSupportDeliveryFactory> all() { return factories; }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("rvp", path); }

    /** 垂直投送在阶段 B 使用的不可变参数。 */
    public record VerticalProjectileData(
            /** 相对计划落点地面的生成高度，单位格。 */ double spawnHeightAboveImpactMeters,
            /** 入场初速度，单位格/Tick。 */ double entrySpeedMetersPerTick,
            /** 生成前预加载等待时间，单位 Tick。 */ int preloadTicks,
            /** 入场方向随机扰动最大角，单位度。 */ double headingJitterDegrees) {}

    private static final class VerticalProjectileFactory implements RVP_FireSupportDeliveryFactory {
        @Override public ResourceLocation typeId() { return VERTICAL_PROJECTILE; }

        @Override
        public Object parse(JsonObject data, RVP_FireSupportProblemCollector problems, String path) {
            RVP_FireSupportJson.keys(data, Set.of("spawn_height_above_impact_m", "entry_speed_m_per_tick",
                    "preload_ticks", "heading_jitter_deg"), path, problems);
            double height = RVP_FireSupportJson.number(data, "spawn_height_above_impact_m", 120.0, path, problems);
            double speed = RVP_FireSupportJson.number(data, "entry_speed_m_per_tick", 4.0, path, problems);
            int preload = RVP_FireSupportJson.integer(data, "preload_ticks", 10, path, problems);
            double jitter = RVP_FireSupportJson.number(data, "heading_jitter_deg", 0.0, path, problems);
            if (height <= 0 || height > 2048) problems.add(path + ".spawn_height_above_impact_m", "必须在 (0, 2048] 内");
            if (speed <= 0 || speed > 64) problems.add(path + ".entry_speed_m_per_tick", "必须在 (0, 64] 内");
            if (preload < 0 || preload > 1200) problems.add(path + ".preload_ticks", "必须在 [0, 1200] 内");
            if (jitter < 0 || jitter > 45) problems.add(path + ".heading_jitter_deg", "必须在 [0, 45] 内");
            return new VerticalProjectileData(height, speed, preload, jitter);
        }

        @Override
        public void validateWeapon(ResourceLocation weaponId, RVP_FireSupportResolvedWeapon data,
                                   RVP_FireSupportProblemCollector problems, String path) {
            RVP_EnumWeaponKind kind = data.kind();
            if (kind == null || kind == RVP_EnumWeaponKind.LASER || kind == RVP_EnumWeaponKind.TARGETING_POD) {
                problems.add(path, "武器 " + weaponId + " 不是可投送的 RVP 实体弹体类型");
            }
            if (data.humanInTheLoop() || data.operatorGuided() || data.hitlClosTvGuided()) {
                problems.add(path, "武器 " + weaponId + " 依赖实时操作手/武器站制导，垂直投送不支持");
            }
        }
    }
}
