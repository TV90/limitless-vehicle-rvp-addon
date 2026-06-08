package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.ywzj.rvp.ext.WeaponUnitSeekerExt;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Allows RVP seeker HUD to power down when the active weapon no longer supports {@link WeaponUnit#toggleSeeker}.
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitSeekerForceOffMixin implements WeaponUnitSeekerExt {

    @Shadow
    private boolean seekerOn;

    @Shadow
    private int lockCoolingTick;

    @Override
    public void ywzj_rvp$forceSeekerOff() {
        if (!seekerOn) {
            return;
        }
        seekerOn = false;
        lockCoolingTick = 0;
        ((WeaponUnit) (Object) this).setLockedEntity((Entity) null);
    }

    @Override
    public void ywzj_rvp$ensureSeekerOn() {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!self.getCurrentWeapon().map(w -> w.withSeeker()).orElse(false)) {
            return;
        }
        seekerOn = true;
    }
}
