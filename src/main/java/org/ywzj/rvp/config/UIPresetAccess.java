package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 按载具实例解析 UI 预设组件位置的公共入口：
 * 载具 id → {@link VehicleUIPresetCache} 预设名 → {@link UIPresetManager} 组件位置。
 * 供各 HUD overlay 消费（双端安全：仅数据层，无客户端类型引用）。
 */
public final class UIPresetAccess {

    private UIPresetAccess() {
    }

    /** 解析载具的 APS 状态 HUD 位置；未配置预设/组件返回 null。 */
    @Nullable
    public static UIPosition apsHud(AbstractVehicle vehicle) {
        return position(vehicle, UIPresetManager::getApsHud);
    }

    /** 解析载具的 DIRCM 通道 HUD 位置；未配置预设/组件返回 null。 */
    @Nullable
    public static UIPosition dircmHud(AbstractVehicle vehicle) {
        return position(vehicle, UIPresetManager::getDircmHud);
    }

    private static UIPosition position(@Nullable AbstractVehicle vehicle,
                                       java.util.function.Function<String, UIPosition> getter) {
        if (vehicle == null) {
            return null;
        }
        ResourceLocation vehicleId = vehicle.getVehicleId();
        String presetName = vehicleId != null ? VehicleUIPresetCache.get(vehicleId) : null;
        return presetName == null ? null : getter.apply(presetName);
    }
}