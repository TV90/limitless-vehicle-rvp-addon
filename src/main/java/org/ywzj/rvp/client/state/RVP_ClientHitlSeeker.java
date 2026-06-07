package org.ywzj.rvp.client.state;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_HitlSeekerUtil;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

public final class RVP_ClientHitlSeeker {

    private RVP_ClientHitlSeeker() {}

    public static float maxLookOffsetDeg(RVP_MissileEntity missile) {
        return missile.rvp$getHitlMaxLookOffsetDeg();
    }

    public static float resolveMaxLookOffsetDeg(RVP_BaseBullet bullet, RVP_WeaponData data) {
        return RVP_HitlSeekerUtil.resolveMaxLookOffsetDeg(data);
    }
}
