package org.ywzj.rvp.mixin;

import com.google.gson.annotations.SerializedName;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleDataPojo;

/**
 * 为载具 JSON POJO 添加 {@code ui_preset} 字段（Gson 自动反序列化）。
 * 载具 JSON 顶层添加 {@code "ui_preset": "preset_name"} 即可指定 UI 预设。
 * <p>
 * 后续 mixin 通过反射读取该字段值，避免使用 {@code @Implements}（Connector 下不兼容）。
 * 本 mixin 无 {@code @Inject} 方法，不会导致 Connector 崩溃。
 * </p>
 */
@Mixin(value = BaseVehicleDataPojo.class, remap = false)
public class BaseVehicleDataPojoMixin {

    @SerializedName("ui_preset")
    @Unique
    public String ywzj_rvp$uiPreset = "";
}
