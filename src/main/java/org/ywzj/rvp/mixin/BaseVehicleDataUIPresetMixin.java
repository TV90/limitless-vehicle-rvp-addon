package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleDataPojo;

import java.lang.reflect.Field;

/**
 * 在 build(pojo) 时读取 POJO 的 ui_preset 字段，存入本 Mixin 的
 * {@code @Unique} 字段 {@link #ywzj_rvp$uiPreset} 中。
 * <p>
 * {@code AbstractVehicleUIPresetMixin} 再通过反射从 {@code BaseVehicleData}
 * 读取该字段写入 {@code VehicleUIPresetCache}。
 * </p>
 */
@Mixin(value = BaseVehicleData.class, remap = false)
public class BaseVehicleDataUIPresetMixin {

    @Unique
    public String ywzj_rvp$uiPreset = "";

    @Inject(method = "build", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$storeOnData(BaseVehicleDataPojo pojo, CallbackInfo ci) {
        this.ywzj_rvp$uiPreset = readPojoField(pojo);
    }

    /** 通过反射读取 target 对象的 {@code ywzj_rvp$uiPreset} 字段 */
    public static String readFrom(BaseVehicleData target) {
        try {
            Field f = BaseVehicleData.class.getDeclaredField("ywzj_rvp$uiPreset");
            f.setAccessible(true);
            Object v = f.get(target);
            return v != null ? (String) v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String readPojoField(BaseVehicleDataPojo pojo) {
        try {
            Field f = BaseVehicleDataPojo.class.getDeclaredField("ywzj_rvp$uiPreset");
            f.setAccessible(true);
            Object v = f.get(pojo);
            return v != null ? (String) v : "";
        } catch (Exception e) {
            return "";
        }
    }
}
