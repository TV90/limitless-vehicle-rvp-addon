package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.util.ResourceScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [RVP] 载具 JSON 里的 RVP 扩展字段解析器（2026-10-30 替代 {@code VehicleDataManagerMixin}）。
 *
 * <p>本类只做一件事：扫描 {@code data/<ns>/vehicles/*.json} 的**原始 JSON 字面量**，
 * 把 RVP 自定义字段（{@code ui_preset} / {@code tactical_map_icon} / {@code rvp_part_hidden_bones} /
 * {@code deployable_uav_*} / {@code rvp_loiter_*} / {@code launcher_deploy} / {@code rvp_auto_landing_gear*} /
 * {@code rvp_radar_rcs_factor} / {@code rvp_custom_mounts} / 武器过热 / {@code rvp_hide_in_creative}）
 * 解析进各自的静态缓存。</p>
 *
 * <p>为什么不再 mixin {@code VehicleDataManager.apply}：本类不依赖本体解析结果（只读 JSON 原文），
 * 因此不需要"贴在 apply 末尾"，只要在数据重载批次里跑一次即可。三条触发路径对应三种场景：</p>
 * <ul>
 *   <li><b>服务端 / 单人</b>：{@link AddReloadListenerEvent}（本类自身就是一个数据重载监听器）；</li>
 *   <li><b>专用服务器客户端</b>：{@code S2CVehicleRvpConfig} 收到同步的原始 JSON 后调
 *       {@link #applyFromJsonMap}（与 {@code RVP_VehicleExtendedConfigManager} 同一套办法）；</li>
 *   <li><b>客户端资源重载 / {@code /ywzj_vehicle reload}</b>：见 {@code RVP_CustomMountReloadListener.apply}
 *       与 {@code RVP_ClientEvents.onClientTickDisplayReload} 里的 {@link #reloadFrom} 调用。</li>
 * </ul>
 *
 * <p>空表守卫：客户端资源栈/数据仓库在某些时机会扫不到 rvp 载具包（{@code VehiclePackLoader} 以资源包注册），
 * 此时直接 return，避免把已填充的缓存清空。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VehicleSupplementListener
        extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_VehicleSupplementListener INSTANCE = new RVP_VehicleSupplementListener();

    private RVP_VehicleSupplementListener() {}

    @Override
    protected @NotNull Map<ResourceLocation, JsonElement> prepare(@NotNull ResourceManager resourceManager,
                                                                @NotNull ProfilerFiller profiler) {
        // 目的：与本体数据仓库同批扫描载具 JSON 目录（含 VehiclePackLoader 注册的载具包）
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(@NotNull Map<ResourceLocation, JsonElement> map,
                         @NotNull ResourceManager resourceManager,
                         @NotNull ProfilerFiller profiler) {
        applyFromJsonMap(map, resourceManager);
    }

    /**
     * 用一份原始载具 JSON 表填充全部 RVP 扩展缓存。
     *
     * @param resources 载具 id → 原始 JSON；为空时直接返回（不清缓存）
     * @param manager   资源仓库，可为 null（专用服客户端同步路径没有本地仓库时传 null）
     */
    public void applyFromJsonMap(@Nullable Map<ResourceLocation, JsonElement> resources,
                                 @Nullable ResourceManager manager) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        Map<ResourceLocation, List<RVP_CustomMountConfig>> customMountsByVehicle = new HashMap<>();
        Map<ResourceLocation, AutoLandingGearCache.AutoLandingGearConfig> autoGearByVehicle = new HashMap<>();
        Map<ResourceLocation, List<RVP_DeployableUavCatalog.Option>> uavOptionsByParent = new HashMap<>();
        Map<ResourceLocation, String> uavDefaultByParent = new HashMap<>();
        Map<ResourceLocation, Map<RVP_VehicleWeaponHeatConfigCache.SlotKey, RVP_VehicleWeaponHeatConfig>>
                weaponHeatByVehicle = new HashMap<>();
        RVP_DeployableUavConfigCache.clear();
        RVP_DeployableUavCatalog.clear();
        RVP_LauncherDeployConfigCache.clear();
        // 分角度 RCS 雷达隐身：载具剖面缓存随数据包全量重建
        org.ywzj.rvp.radar.RVP_AspectRcs.clear();
        // 残件隐藏骨：rvp_part_hidden_bones 缓存随数据包全量重建
        RVP_PartHiddenBonesCache.clear();
        // 创造栏隐藏表：rvp_hide_in_creative 随数据包全量重建
        RVP_CreativeHiddenVehicles.clear();
        for (var entry : resources.entrySet()) {
            ResourceLocation vehicleId = entry.getKey();
            JsonElement json = entry.getValue();
            try {
                var obj = GsonHelper.convertToJsonObject(json, "vehicle data");

                // ui_preset
                String uiPreset = GsonHelper.getAsString(obj, "ui_preset", "");
                if (!uiPreset.isEmpty()) {
                    VehicleUIPresetCache.put(vehicleId, uiPreset);
                }
                VehicleUIPresetCache.putNctrName(vehicleId, GsonHelper.getAsString(obj, "nctr_name", "?"));

                // tactical_map_icon：战术地图/轻量炮兵地图的载具图标名（不带扩展名时按 map_icon 目录解析）；
                // 留空则清除，回落到载具类型默认图标
                VehicleUIPresetCache.putTacticalMapIcon(vehicleId,
                        GsonHelper.getAsString(obj, "tactical_map_icon", ""));

                // show_skeleton（观瞄时骨骼俯视图，默认 true）
                VehicleUIPresetCache.putShowSkeleton(vehicleId,
                        GsonHelper.getAsBoolean(obj, "show_skeleton", true));

                // 创造栏隐藏：仅母车部署的辅助载具（雷达车 / 随车无人机）不给直接刷出入口
                if (GsonHelper.getAsBoolean(obj, "rvp_hide_in_creative", false)) {
                    RVP_CreativeHiddenVehicles.add(vehicleId);
                }

                // 残件隐藏骨：rvp_part_hidden_bones = { "<partId>": ["bone", ...], ... }
                Map<String, List<String>> partHiddenBones = new HashMap<>();
                JsonElement hiddenEl = obj.get("rvp_part_hidden_bones");
                if (hiddenEl != null && hiddenEl.isJsonObject()) {
                    for (var e : hiddenEl.getAsJsonObject().entrySet()) {
                        if (e.getValue() == null || !e.getValue().isJsonArray()) continue;
                        List<String> bones = new ArrayList<>();
                        for (JsonElement b : e.getValue().getAsJsonArray()) {
                            if (b != null && b.isJsonPrimitive()) {
                                String name = b.getAsString().trim();
                                if (!name.isEmpty()) bones.add(name);
                            }
                        }
                        if (!bones.isEmpty()) partHiddenBones.put(e.getKey(), List.copyOf(bones));
                    }
                }
                // 空表也要 put，便于重载时清干净
                RVP_PartHiddenBonesCache.put(vehicleId, partHiddenBones);

                RVP_DeployableUavOptionsParser.Parsed parsedUavOptions = RVP_DeployableUavOptionsParser.parse(obj);
                if (!parsedUavOptions.options().isEmpty()) {
                    uavOptionsByParent.put(vehicleId, parsedUavOptions.options());
                    uavDefaultByParent.put(vehicleId, parsedUavOptions.defaultOptionId());
                }

                RVP_LoiterConfig loiterConfig = parseLoiterConfig(obj);
                if (loiterConfig.isConfigured()) {
                    RVP_LoiterConfigCache.put(vehicleId, loiterConfig);
                }

                List<RVP_LauncherDeployConfig> launcherDeployConfigs = RVP_LauncherDeployConfig.parseList(obj);
                if (!launcherDeployConfigs.isEmpty()) {
                    RVP_LauncherDeployConfigCache.put(vehicleId, launcherDeployConfigs);
                }
                // 自动收放起落架
                if (GsonHelper.getAsBoolean(obj, "rvp_auto_landing_gear", false)) {
                    double retractSpeed = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_retract_speed", 100);
                    double deploySpeed = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_deploy_speed", 50);
                    double deployHeight = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_deploy_height", 25);
                    double retractHeight = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_retract_height", 50);
                    autoGearByVehicle.put(vehicleId, new AutoLandingGearCache.AutoLandingGearConfig(
                            true, retractSpeed, deploySpeed, deployHeight, retractHeight));
                }
                // 分角度 RCS：解析 rvp_radar_rcs_factor 与弹舱 open_radar_rcs_multiplier
                org.ywzj.rvp.radar.RVP_AspectRcs.parse(vehicleId, obj);
                List<RVP_CustomMountConfig> customMounts = RVP_CustomMountConfig.parseList(obj);
                if (customMounts != null) {
                    customMountsByVehicle.put(vehicleId, customMounts);
                }
                Map<RVP_VehicleWeaponHeatConfigCache.SlotKey, RVP_VehicleWeaponHeatConfig> heatConfigs =
                        RVP_VehicleWeaponHeatConfigCache.parseVehicle(obj);
                if (!heatConfigs.isEmpty()) {
                    weaponHeatByVehicle.put(vehicleId, heatConfigs);
                }
            } catch (Exception ignored) {
                // JSON 解析错误，跳过（与迁移前一致：单台载具出错不影响其它载具）
            }
        }
        RVP_DeployableUavCatalog.replace(uavOptionsByParent, uavDefaultByParent);
        RVP_CustomMountConfigCache.replace(customMountsByVehicle);
        RVP_VehicleWeaponHeatConfigCache.replace(weaponHeatByVehicle);
        AutoLandingGearCache.replace(autoGearByVehicle);
        org.ywzj.rvp.mount.RVP_ShootBoltQueueResolver.applyTemplateBolts(resources);
        // 重载 UI 预设（配合 /ywzj_vehicle reload 热更新）；manager 为 null 时只回退 config 目录
        // 重载 UI 预设（配合 /ywzj_vehicle reload 热更新）。
        // 只在有真实资源仓库时调用：UIPresetManager.load(null) 会清空已加载的数据包预设（专用服 S2C 路径没有本地仓库）。
        if (manager != null) {
            UIPresetManager.load(manager);
        }
    }

    /** 用指定资源仓库手动重载（客户端资源重载 / {@code /ywzj_vehicle reload} 调用）。 */
    public void reloadFrom(@Nullable ResourceManager resourceManager) {
        if (resourceManager == null) {
            return;
        }
        applyFromJsonMap(ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON),
                resourceManager);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    private static RVP_LoiterConfig parseLoiterConfig(JsonObject vehicleObj) {
        boolean enabled = GsonHelper.getAsBoolean(vehicleObj, "rvp_loiter_enabled", false);
        if (!enabled) {
            return RVP_LoiterConfig.DISABLED;
        }
        // 2026-10-06 精简：仅 4 键；旧 JSON 多写的 rvp_loiter_terrain_clearance / min_safe_altitude /
        // fixed_wing_min_bank / bank / direction 键被 GsonHelper 忽略（算法参数已收敛为 RVP_LoiterConfig 常量）
        return new RVP_LoiterConfig(
                true,
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_radius", 120.0),
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_altitude_offset", 40.0),
                GsonHelper.getAsBoolean(vehicleObj, "rvp_loiter_auto_on_takeoff", false)
        );
    }
}
