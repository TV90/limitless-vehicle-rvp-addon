package org.ywzj.rvp.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

@Mixin(value = SwitchableUnit.class, remap = false)
public interface SwitchableUnitAccessor {

    @Accessor("on")
    void setOnField(boolean on);

    @Accessor("on")
    boolean isOnField();
}
