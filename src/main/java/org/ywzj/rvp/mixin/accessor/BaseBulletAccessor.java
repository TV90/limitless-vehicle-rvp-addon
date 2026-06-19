package org.ywzj.rvp.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

@Mixin(value = RVP_BaseBullet.class, remap = false)
public interface BaseBulletAccessor {

    @Accessor("motorBurnEndTick")
    int getMotorBurnEndTick();

    @Accessor("showMslIndicator")
    boolean isShowMslIndicator();
}
