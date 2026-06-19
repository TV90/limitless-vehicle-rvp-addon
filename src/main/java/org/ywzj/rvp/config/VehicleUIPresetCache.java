package org.ywzj.rvp.config;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * UI 预设名缓存，以 {@code WeakHashMap} 存储每个载具实体的预设名。
 * <p>
 * 替换 {@code @Implements(@Interface)} 方案（在 Connector 环境下不兼容），
 * 改用 Mixin 写入 + 直接读取 Map 的方式传递预设名。
 * </p>
 */
public final class VehicleUIPresetCache {

    private static final Map<AbstractVehicle, String> CACHE = new WeakHashMap<>();

    public static void put(AbstractVehicle vehicle, String presetName) {
        CACHE.put(vehicle, presetName);
    }

    /**
     * 获取载具的 UI 预设名，不存在返回 {@code null}。
     */
    public static String get(AbstractVehicle vehicle) {
        return CACHE.get(vehicle);
    }

    private VehicleUIPresetCache() {}
}
