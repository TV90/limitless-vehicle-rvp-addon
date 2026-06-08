package org.ywzj.rvp.radar;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

/**
 * Fire-control radar supplement: track slow / ground vehicles that vanilla velocity gate drops.
 */
public final class RVP_RadarGroundScan {

    private RVP_RadarGroundScan() {}

    public static void supplementGroundContacts(RadarUnit radar) {
        if (radar == null || !radar.isOn() || !radar.getVehicle().hasPower()) {
            return;
        }
        RadarUnitData data = radarData(radar);
        if (!(data instanceof RadarUnitDataExt ext) || !ext.ywzj_rvp$isTrackGroundTargets()) {
            return;
        }
        AbstractVehicle owner = radar.getVehicle();
        Vec3 radarPos = radar.worldRadarPosition();
        double maxDist = radar.getMaxScanDistance();
        AABB box = new AABB(
                radarPos.x - maxDist, radarPos.y - maxDist, radarPos.z - maxDist,
                radarPos.x + maxDist, radarPos.y + maxDist, radarPos.z + maxDist);
        float yRotSpeed = radar.getYRotSpeed();
        for (Entity entity : owner.level().getEntities(owner, box, e -> e instanceof AbstractVehicle)) {
            AbstractVehicle target = (AbstractVehicle) entity;
            if (target == owner || target.isDestroyed() || !target.isAlive()) {
                continue;
            }
            if (target.getBoundingBox().getSize() < 1) {
                continue;
            }
            float rcs = target.physicsEngine.radarCrossSection;
            if (target.distanceToSqr(owner) > maxDist * maxDist * rcs * rcs) {
                continue;
            }
            Vec3 center = target.getBoundingBox().getCenter();
            if (!inScanSector(radar, center, yRotSpeed)) {
                continue;
            }
            radar.detect(target);
        }
    }

    private static boolean inScanSector(RadarUnit radar, Vec3 worldPos, float yRotSpeed) {
        Vec2 aimRot = radar.aimRot(worldPos);
        if (aimRot.y < radar.getYRotMin() || aimRot.y > radar.getYRotMax()) {
            return false;
        }
        if (yRotSpeed > 0 && Math.abs(aimRot.y - radar.getYRot()) > yRotSpeed / 2.0f) {
            return false;
        }
        return Math.abs(aimRot.x - radar.getXRot()) <= radar.getScanSectorAngle() / 2.0f;
    }

    private static RadarUnitData radarData(RadarUnit radar) {
        return (RadarUnitData) ((org.ywzj.rvp.mixin.PartUnitAccessorMixin) (Object) radar).ywzj_rvp$getData();
    }
}
