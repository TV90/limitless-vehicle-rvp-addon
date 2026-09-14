package org.ywzj.rvp.mixin.accessor;

import org.ywzj.vehicle.custom.part.PartUnitEntry;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * [RVP] {@link BaseVehicleData} 部件模板列表访问器（读 protected parts）。
 * 出弹点数据层写入（挂骨方案）经此遍历武器站模板并改写其 bolts。
 */
@Mixin(value = BaseVehicleData.class, remap = false)
public interface BaseVehicleDataAccessor {

    @Accessor("parts")
    List<PartUnitEntry<?, ?>> ywzj_rvp$getParts();
}
