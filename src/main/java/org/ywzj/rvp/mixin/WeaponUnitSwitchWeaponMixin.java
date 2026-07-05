package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * 切换武器时，如果新武器没有寻的头，自动关闭导引头。
 * 防止从 SARH/IR 导弹切到 SACLOS 时导引头状态残留。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSwitchWeaponMixin {

    @Shadow(remap = false) private boolean seekerOn;

    @Inject(
            method = "setCurrentWeaponIndex",
            at = @At("TAIL"),
            remap = false
    )
    private void ywzj_rvp$resetSeekerOnWeaponSwitch(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        ywzj_rvp$syncAfterPrimarySwitch(self);
    }

    @Inject(
            method = "setCurrentSecondaryWeaponIndex",
            at = @At("TAIL"),
            remap = false
    )
    private void ywzj_rvp$resetSeekerOnSecondaryWeaponSwitch(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        ywzj_rvp$syncAfterSecondarySwitch(self);
    }

    @Inject(
            method = "switchWeapon",
            at = @At("TAIL"),
            remap = false
    )
    private void ywzj_rvp$restoreLockOnRealWeaponSwitch(boolean secondary, boolean next, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (secondary) {
            ywzj_rvp$syncAfterSecondarySwitch(self);
            return;
        }
        ywzj_rvp$syncAfterPrimarySwitch(self);
    }

    private void ywzj_rvp$syncAfterPrimarySwitch(WeaponUnit self) {
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isPresent() && !weaponOpt.get().withSeeker()) {
            seekerOn = false;
        }
        ywzj_rvp$restoreRadarLockOnWeaponSwitch(self);
    }

    private void ywzj_rvp$syncAfterSecondarySwitch(WeaponUnit self) {
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentSecondaryWeapon();
        if (weaponOpt.isPresent() && !weaponOpt.get().withSeeker()) {
            seekerOn = false;
        }
        ywzj_rvp$restoreRadarLockOnWeaponSwitch(self);
    }

    private static void ywzj_rvp$restoreRadarLockOnWeaponSwitch(WeaponUnit self) {
        WeaponUnit root = self.getRootParentWeaponUnit();
        if (root.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            return;
        }
        RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(root);
        if (radar == null) {
            return;
        }
        Entity radarLocked = radar.getLockedEntity();
        if (radarLocked != null && radarLocked.isAlive()) {
            root.setLockedEntity(radarLocked);
        }
    }
}
