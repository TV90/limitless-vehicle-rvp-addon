package org.ywzj.rvp.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.RVP_ApsConfig;
import org.ywzj.rvp.config.RVP_ApsConfigCache;
import org.ywzj.rvp.config.RVP_DeployableUavConfig;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.custom.VehicleDataManager;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
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
        RVP_DeployableUavConfigCache.clear();
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

                RVP_ApsConfigCache.put(vehicleId, ywzj_rvp$parseApsConfig(obj));
                // 自动收放起落架
                if (GsonHelper.getAsBoolean(obj, "rvp_auto_landing_gear", false)) {
                    double retractSpeed = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_retract_speed", 100);
                    double deploySpeed = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_deploy_speed", 50);
                    double deployHeight = GsonHelper.getAsDouble(obj, "rvp_auto_landing_gear_deploy_height", 25);
                    autoGearByVehicle.put(vehicleId, new AutoLandingGearCache.AutoLandingGearConfig(
                            true, retractSpeed, deploySpeed, deployHeight));
                }
                List<RVP_CustomMountConfig> customMounts = RVP_CustomMountConfig.parseList(obj);
                if (customMounts != null) {
                    customMountsByVehicle.put(vehicleId, customMounts);
                }
            } catch (Exception ignored) {
                // JSON 解析错误，跳过
            }
        }
        RVP_CustomMountConfigCache.replace(customMountsByVehicle);
        AutoLandingGearCache.replace(autoGearByVehicle);
        // [RVP] 重载 UI 预设（配合 /ywzj_vehicle reload 热更新）
        UIPresetManager.load(manager);
    }

    private static RVP_ApsConfig ywzj_rvp$parseApsConfig(JsonObject vehicleObj) {
        if (!vehicleObj.has("rvp_aps") || !vehicleObj.get("rvp_aps").isJsonObject()) {
            return RVP_ApsConfig.DISABLED;
        }

        JsonObject apsObj = vehicleObj.getAsJsonObject("rvp_aps");
        boolean enabled = GsonHelper.getAsBoolean(apsObj, "enabled", false);
        if (!enabled) {
            return RVP_ApsConfig.DISABLED;
        }

        List<String> animationPartIds = new ArrayList<>();
        if (apsObj.has("animation_part_ids") && apsObj.get("animation_part_ids").isJsonArray()) {
            for (JsonElement element : apsObj.getAsJsonArray("animation_part_ids")) {
                if (element != null && element.isJsonPrimitive()) {
                    String id = element.getAsString().trim();
                    if (!id.isEmpty()) {
                        animationPartIds.add(id);
                    }
                }
            }
        }

        String spawnPartId = GsonHelper.getAsString(apsObj, "spawn_part_id", "");
        if (spawnPartId.isBlank() && !animationPartIds.isEmpty()) {
            spawnPartId = animationPartIds.get(0);
        }

        double speedMin = Math.max(0.0, GsonHelper.getAsDouble(apsObj, "projectile_speed_min", 1.0));
        double speedMax = Math.max(speedMin, GsonHelper.getAsDouble(apsObj, "projectile_speed_max", 80.0));

        return new RVP_ApsConfig(
                true,
                Math.max(0, GsonHelper.getAsInt(apsObj, "ammo_max", 0)),
                Math.max(1, GsonHelper.getAsInt(apsObj, "reload_one_tick", 600)),
                Math.max(1, GsonHelper.getAsInt(apsObj, "cooldown_tick", 20)),
                Math.max(1, GsonHelper.getAsInt(apsObj, "scan_interval_tick", 1)),
                Math.max(0.0, GsonHelper.getAsDouble(apsObj, "detect_radius", 32.0)),
                Math.max(0.1, GsonHelper.getAsDouble(apsObj, "intercept_radius", 8.0)),
                speedMin,
                speedMax,
                animationPartIds,
                spawnPartId,
                GsonHelper.getAsBoolean(apsObj, "exclude_owner_projectile", true)
        );
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
                autoLinkDatalink
        );
    }
}
