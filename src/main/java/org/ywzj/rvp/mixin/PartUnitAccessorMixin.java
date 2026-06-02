package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.vehicle.part.PartUnit;

@Mixin(value = PartUnit.class, remap = false)
public interface PartUnitAccessorMixin {
    @Accessor(value = "data", remap = false)
    PartUnitData ywzj_rvp$getData();
}
