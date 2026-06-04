package org.ywzj.rvp.client.laser;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.ActiveLaser;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.core.RVP_LaserWeapon;
import org.ywzj.rvp.weapon.data.RVP_LaserVisualData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import javax.annotation.Nullable;

/**
 * Resolves live laser beam geometry for client rendering and impact FX.
 */
public final class RVP_ClientLaserBeamResolver {

    public record ResolvedBeam(RVP_LaserBeam beam, RVP_LaserVisualData visual, RVP_WeaponData data) {}

    private RVP_ClientLaserBeamResolver() {}

    @Nullable
    public static ResolvedBeam resolve(Level level, LaserBeamKey key, ActiveLaser active) {
        return resolve(level, key, active, 1.0F);
    }

    @Nullable
    public static ResolvedBeam resolve(Level level, LaserBeamKey key, ActiveLaser active, float partialTick) {
        Entity entity = level.getEntity(key.vehicleId());
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return null;
        }
        WeaponUnit registryUnit = RVP_LaserWeapons.registryUnit(vehicle, key);
        RVP_LaserWeapon laserWeapon = RVP_LaserWeapons.resolveLaser(registryUnit, key.weaponIndex());
        if (laserWeapon == null || !RVP_LaserWeapons.canRenderBeam(laserWeapon)) {
            return null;
        }

        RVP_WeaponData data = laserWeapon.getData();
        WeaponUnit aimUnit = RVP_LaserWeapons.aimUnit(laserWeapon);
        AimContext aim = RVP_WeaponAimInterpolation.aimContext(aimUnit, vehicle, partialTick);
        float range = Math.max(data.getLaserRange(), data.getSeekerData().getRange());

        LivingEntity shooter = null;
        int operatorId = RVP_ClientLaserState.operatorIdOf(active);
        if (operatorId >= 0) {
            Entity op = level.getEntity(operatorId);
            if (op instanceof LivingEntity living) {
                shooter = living;
            }
        }

        RVP_LaserVisualData visual = RVP_ClientLaserState.visualOf(active);
        RVP_LaserBeam beam = RVP_LaserBeamGeometry.compute(
                level, vehicle, shooter, RVP_AimContexts.muzzle(aim),
                VectorUtil.rotToVec(aim.direction.x, aim.direction.y),
                range, visual);

        return new ResolvedBeam(beam, visual, data);
    }
}
