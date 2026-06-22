package org.ywzj.rvp.mixin;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.UIPresetManager;
import org.ywzj.rvp.config.VehicleUIPresetCache;
import org.ywzj.vehicle.custom.VehicleDataManager;

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

                // show_skeleton（观瞄时骨骼俯视图，默认 true）
                VehicleUIPresetCache.putShowSkeleton(vehicleId,
                        GsonHelper.getAsBoolean(obj, "show_skeleton", true));
            } catch (Exception ignored) {
                // JSON 解析错误，跳过
            }
        }
        // [RVP] 重载 UI 预设（配合 /ywzj_vehicle reload 热更新）
        UIPresetManager.load(manager);
    }
}
