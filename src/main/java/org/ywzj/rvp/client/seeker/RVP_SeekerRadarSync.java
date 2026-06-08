package org.ywzj.rvp.client.seeker;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Keeps fire-control radar illumination aligned with SARH seeker HUD lock.
 */
public final class RVP_SeekerRadarSync {

    private RVP_SeekerRadarSync() {}

    public static void syncIllumination(WeaponUnit operatorUnit, Entity target) {
        RadarUnit radar = RVP_SeekerWeaponUtil.resolveFireControlRadar(operatorUnit);
        if (radar == null) {
            return;
        }
        Entity current = radar.getLockedEntity();
        if (target == null) {
            if (current != null) {
                radar.setLockedEntity(null);
            }
            return;
        }
        RadarUnit.DetectedObject detected = radar.getDetectedEntities().get(target.getId());
        Entity resolved = detected != null && detected.entity != null && detected.entity.isAlive()
                ? detected.entity : target;
        if (current == null || current.getId() != resolved.getId()) {
            radar.setLockedEntity(resolved);
        }
    }
}
