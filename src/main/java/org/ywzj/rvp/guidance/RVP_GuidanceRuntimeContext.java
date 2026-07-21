package org.ywzj.rvp.guidance;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

public record RVP_GuidanceRuntimeContext(
        RVP_BaseBullet projectile,
        RVP_WeaponData data,
        RVP_GuidanceActiveConfig active
) {
}
