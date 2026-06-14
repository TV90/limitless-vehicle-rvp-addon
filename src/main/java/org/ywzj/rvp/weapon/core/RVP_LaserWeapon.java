package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.damage.RVP_DamageApplier;
import org.ywzj.rvp.weapon.damage.RVP_HitboxDamageContext;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.laser.RVP_LaserRaycast;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;
import org.ywzj.vehicle.all.AllDamageTypes;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;

/**
 * Instant ray weapon for {@code rvp:laser}. Damage on server; beam visuals are client-side
 * and follow the live weapon aim via {@link org.ywzj.rvp.client.laser.RVP_ClientLaserState}.
 */
public class RVP_LaserWeapon extends RVP_WeaponBase {

    public RVP_LaserWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    /** Client charge progress for beam preview (not synced). */
    public int getChargeTick() {
        return chargeTick;
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isCoolingDown() || isReloading() || !consumeAmmo(aimContexts)) {
            return false;
        }
        getFireController().primeServerShot();
        if (!canShootOnServer()) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();

        AbstractVehicle vehicle = getVehicle();
        RVP_WeaponData data = getData();
        float chargeScale = consumeChargeScale();
        for (AimContext aim : aimContexts) {
            Vec3 start = RVP_AimContexts.muzzle(aim);
            Vec3 look = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
            float range = data.getLaserRange();

            RVP_LaserBeam beam = RVP_LaserRaycast.computeBeam(
                    vehicle.level(), vehicle, shooter, start, look, range,
                    data.getLaserVisual().getRenderStartDistance());

            if (beam.hitEntity() != null) {
                var source = AllDamageTypes.Sources.bullet(
                        vehicle.level().registryAccess(), shooter, shooter, beam.hitEntity().position());
                float hitDamage = RVP_DamageApplier.applyScaled(
                        data.getDirectDamage() * chargeScale, beam.hitEntity(), data);
                if (beam.hitEntity() instanceof AbstractVehicle targetVehicle) {
                    var res = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(
                            targetVehicle, start, beam.impactPoint());
                    float before = hitDamage;
                    hitDamage *= res.factor();
                    if (shooter instanceof net.minecraft.world.entity.player.Player player) {
                        RVP_VehicleHitboxFactorManager.INSTANCE.maybeSendHitboxDebug(
                                player, targetVehicle, before, hitDamage, res,
                                Float.NaN, 1f
                        );
                    }
                }
                RVP_HitboxDamageContext.pushSkipGlobalVehicleHurtScaling();
                try {
                    EntityUtil.hurt(source, beam.hitEntity(), hitDamage);
                } finally {
                    RVP_HitboxDamageContext.popSkipGlobalVehicleHurtScaling();
                }
            }
            vehicle.physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
        }
        getFireController().onShotFired();
        return true;
    }
}
