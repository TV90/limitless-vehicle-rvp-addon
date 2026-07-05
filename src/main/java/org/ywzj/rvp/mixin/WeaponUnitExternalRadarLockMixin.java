package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitExternalRadarLockMixin implements WeaponUnitExternalRadarLockExt {
    @Unique
    private int ywzj_rvp$externalRadarRequestedEntityId = Integer.MIN_VALUE;
    @Unique
    private int ywzj_rvp$externalRadarLockedEntityId = Integer.MIN_VALUE;

    @Override
    public int ywzj_rvp$getExternalRadarRequestedEntityId() {
        return ywzj_rvp$externalRadarRequestedEntityId;
    }

    @Override
    public void ywzj_rvp$setExternalRadarRequestedEntityId(int entityId) {
        ywzj_rvp$externalRadarRequestedEntityId = entityId;
    }

    @Override
    public void ywzj_rvp$clearExternalRadarRequestedEntityId() {
        ywzj_rvp$externalRadarRequestedEntityId = Integer.MIN_VALUE;
    }

    @Override
    public int ywzj_rvp$getExternalRadarLockedEntityId() {
        return ywzj_rvp$externalRadarLockedEntityId;
    }

    @Override
    public void ywzj_rvp$setExternalRadarLockedEntityId(int entityId) {
        ywzj_rvp$externalRadarLockedEntityId = entityId;
    }

    @Override
    public void ywzj_rvp$clearExternalRadarLockedEntityId() {
        ywzj_rvp$externalRadarLockedEntityId = Integer.MIN_VALUE;
    }
}
