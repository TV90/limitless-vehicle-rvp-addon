package org.ywzj.rvp.mixin;

import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.StructureModelManager;
import org.ywzj.vehicle.custom.VehicleDataManager;
import org.ywzj.vehicle.custom.VehicleWeaponManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link CommonAssetsManagerSyncMixin} 的配套访问器：本体三张缓存管理器字段为
 * private final，经此 Accessor 读取。Mixin 0.8.5 的字段 Accessor 只支持
 * "实例方法 0 参"形态（静态方法带接收者参数会抛 InvalidAccessorException 致
 * 混入 FATAL），调用处对 {@code CommonAssetsManager.INSTANCE} 做鸭子类型转换。
 */
@Mixin(value = CommonAssetsManager.class, remap = false)
public interface CommonAssetsManagerAccessor {

    @Accessor(value = "structureModelManager", remap = false)
    StructureModelManager rvp$structureModelManager();

    @Accessor(value = "vehicleWeaponManager", remap = false)
    VehicleWeaponManager rvp$vehicleWeaponManager();

    @Accessor(value = "vehicleDataManager", remap = false)
    VehicleDataManager rvp$vehicleDataManager();
}
