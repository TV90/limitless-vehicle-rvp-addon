package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.WeaponUnitPendingRadarLockExt;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitPendingRadarLockMixin implements WeaponUnitPendingRadarLockExt {
    @Unique
    private int ywzj_rvp$pendingRadarLockEntityId = Integer.MIN_VALUE;

    @Override
    public int ywzj_rvp$getPendingRadarLockEntityId() {
        return ywzj_rvp$pendingRadarLockEntityId;
    }

    @Override
    public void ywzj_rvp$setPendingRadarLockEntityId(int entityId) {
        ywzj_rvp$pendingRadarLockEntityId = entityId;
    }

    @Override
    public void ywzj_rvp$clearPendingRadarLockEntityId() {
        ywzj_rvp$pendingRadarLockEntityId = Integer.MIN_VALUE;
    }
}
