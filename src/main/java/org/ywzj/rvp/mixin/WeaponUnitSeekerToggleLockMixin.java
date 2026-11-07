package org.ywzj.rvp.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 开启导引头时保留光电/RF 站级锁：本体 toggleSeeker 开机必清 lockedEntity，
 * 会把光电锁和 IR 头瞄的自动引导 cue 一并断掉。仅开机且当前传感器为 EO/RF 时跳过清锁。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitSeekerToggleLockMixin {

    @OnlyIn(Dist.CLIENT)
    @Inject(
            method = "toggleSeeker",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;setLockedEntity(Lnet/minecraft/world/entity/Entity;)V"
            ),
            cancellable = true,
            remap = false
    )
    private void rvp$keepSensorLockOnSeekerOn(Boolean on, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!self.isSeekerOn()) {
            return;
        }
        WeaponUnitData.FireControlSensorType sensor = RVP_WeaponSensorHelper.effectiveSensorType(self);
        if (sensor == WeaponUnitData.FireControlSensorType.EO
                || sensor == WeaponUnitData.FireControlSensorType.RF) {
            ci.cancel();
        }
    }
}
