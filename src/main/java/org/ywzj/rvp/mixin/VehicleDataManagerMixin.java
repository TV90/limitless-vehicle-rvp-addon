package org.ywzj.rvp.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.rvp.config.RVP_VehicleWeaponHeatConfig;
import org.ywzj.rvp.config.RVP_VehicleWeaponHeatConfigCache;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.custom.VehicleDataManager;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 {@link VehicleDataManager#apply} 阶段，从原始 JSON 中读取 {@code ui_preset}
 * 字段，直接存入 {@link VehicleUIPresetCache}。
 * <p>
 * 不依赖任何数据层 mixin，直接从 JSON 字面量读取。
 * </p>
 */
@Mixin(value = VehicleDataManager.class, remap = false)
public class VehicleDataManagerMixin {

    @Inject(method = "apply", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$readUiPresets(Map<ResourceLocation, JsonElement> resources,
                                          ResourceManager manager,
                                          ProfilerFiller profiler,
                                          CallbackInfo ci) {
        Map<ResourceLocation, List<RVP_CustomMountConfig>> customMountsByVehicle = new HashMap<>();
        Map<ResourceLocation, AutoLandingGearCache.AutoLandingGearConfig> autoGearByVehicle = new HashMap<>();
        Map<ResourceLocation, Map<RVP_VehicleWeaponHeatConfigCache.SlotKey, RVP_VehicleWeaponHeatConfig>> weaponHeatByVehicle = new HashMap<>();
        RVP_DeployableUavConfigCache.clear();
        RVP_LauncherDeployConfigCache.clear();
        // 分角度 RCS 雷达隐身：载具剖面缓存随数据包全量重建
        org.ywzj.rvp.radar.RVP_AspectRcs.clear();
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

                // show_skeleton（观瞄时骨骼俯视图，默认 true）
                VehicleUIPresetCache.putShowSkeleton(vehicleId,
                        GsonHelper.getAsBoolean(obj, "show_skeleton", true));

                RVP_DeployableUavConfig deployableUavConfig = ywzj_rvp$parseDeployableUavConfig(obj);
                if (deployableUavConfig.isConfigured()) {
                    RVP_DeployableUavConfigCache.put(vehicleId, deployableUavConfig);
                }

                RVP_LoiterConfig loiterConfig = ywzj_rvp$parseLoiterConfig(obj);
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
                // JSON 解析错误，跳过
            }
        }
        RVP_CustomMountConfigCache.replace(customMountsByVehicle);
        RVP_VehicleWeaponHeatConfigCache.replace(weaponHeatByVehicle);
        AutoLandingGearCache.replace(autoGearByVehicle);
        org.ywzj.rvp.mount.RVP_ShootBoltQueueResolver.applyTemplateBolts(resources);
        // [RVP] 重载 UI 预设（配合 /ywzj_vehicle reload 热更新）
        UIPresetManager.load(manager);
    }

    private static RVP_DeployableUavConfig ywzj_rvp$parseDeployableUavConfig(JsonObject vehicleObj) {
        boolean enabled = GsonHelper.getAsBoolean(vehicleObj, "deployable_uav_enabled", false);
        if (!enabled) {
            return RVP_DeployableUavConfig.DISABLED;
        }

        ResourceLocation vehicleId = ResourceLocation.tryParse(
                GsonHelper.getAsString(vehicleObj, "deployable_uav_vehicle_id", "")
        );
        if (vehicleId == null) {
            return RVP_DeployableUavConfig.DISABLED;
        }

        String role = GsonHelper.getAsString(vehicleObj, "deployable_uav_role", "uav");
        String spawnYawMode = GsonHelper.getAsString(vehicleObj, "deployable_uav_spawn_yaw_mode", "parent");
        boolean singleInstance = GsonHelper.getAsBoolean(vehicleObj, "deployable_uav_single_instance", true);
        boolean allowControlSwitch = GsonHelper.getAsBoolean(vehicleObj, "deployable_uav_allow_control_switch", true);
        boolean autoLinkDatalink = GsonHelper.getAsBoolean(vehicleObj, "deployable_uav_auto_link_datalink", true);
        int redeployCooldownTick = Math.max(0, GsonHelper.getAsInt(vehicleObj, "deployable_uav_redeploy_cooldown_tick", 0));

        Vec3 spawnOffset = Vec3.ZERO;
        if (vehicleObj.has("deployable_uav_spawn_offset")) {
            JsonElement offsetElement = vehicleObj.get("deployable_uav_spawn_offset");
            if (offsetElement.isJsonObject()) {
                JsonObject offsetObj = offsetElement.getAsJsonObject();
                spawnOffset = new Vec3(
                        GsonHelper.getAsDouble(offsetObj, "x", 0.0),
                        GsonHelper.getAsDouble(offsetObj, "y", 0.0),
                        GsonHelper.getAsDouble(offsetObj, "z", 0.0)
                );
            } else if (offsetElement.isJsonArray() && offsetElement.getAsJsonArray().size() >= 3) {
                spawnOffset = new Vec3(
                        offsetElement.getAsJsonArray().get(0).getAsDouble(),
                        offsetElement.getAsJsonArray().get(1).getAsDouble(),
                        offsetElement.getAsJsonArray().get(2).getAsDouble()
                );
            }
        }

        return new RVP_DeployableUavConfig(
                true,
                vehicleId,
                role,
                spawnOffset,
                spawnYawMode,
                singleInstance,
                allowControlSwitch,
                autoLinkDatalink,
                redeployCooldownTick,
                GsonHelper.getAsBoolean(vehicleObj, "deployable_uav_auto_loiter_on_switch_back", true),
                (float) GsonHelper.getAsDouble(vehicleObj, "deployable_uav_initial_speed", 0.0),
                ywzj_rvp$parseAllowedSeatIndexes(vehicleObj)
        );
    }

    /**
     * 解析 {@code deployable_uav_allowed_seat_indexes} 座位索引列表。
     * 缺省/空数组返回空列表，表示仅驾驶位（座位 0）可部署。
     */
    private static java.util.List<Integer> ywzj_rvp$parseAllowedSeatIndexes(JsonObject vehicleObj) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        if (vehicleObj.has("deployable_uav_allowed_seat_indexes")
                && vehicleObj.get("deployable_uav_allowed_seat_indexes").isJsonArray()) {
            for (JsonElement element : vehicleObj.get("deployable_uav_allowed_seat_indexes").getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    int seatIndex = element.getAsInt();
                    if (seatIndex >= 0 && !result.contains(seatIndex)) {
                        result.add(seatIndex);
                    }
                }
            }
        }
        return result;
    }

    @Unique
    private static RVP_LoiterConfig ywzj_rvp$parseLoiterConfig(JsonObject vehicleObj) {
        boolean enabled = GsonHelper.getAsBoolean(vehicleObj, "rvp_loiter_enabled", false);
        if (!enabled) {
            return RVP_LoiterConfig.DISABLED;
        }
        return new RVP_LoiterConfig(
                true,
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_radius", 120.0),
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_altitude_offset", 40.0),
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_terrain_clearance", 30.0),
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_min_safe_altitude", 80.0),
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_fixed_wing_min_bank", 30.0),
                GsonHelper.getAsBoolean(vehicleObj, "rvp_loiter_auto_on_takeoff", false),
                GsonHelper.getAsDouble(vehicleObj, "rvp_loiter_bank", 25.0),
                "left".equalsIgnoreCase(GsonHelper.getAsString(vehicleObj, "rvp_loiter_direction", "right")) ? -1 : 1
        );
    }
}
