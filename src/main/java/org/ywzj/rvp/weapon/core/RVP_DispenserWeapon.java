package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * Dispenser weapon. It emits payload projectiles through the same RVP pipeline.
 */
public class RVP_DispenserWeapon extends RVP_WeaponBase {

    private final Supplier<EntityType<? extends Projectile>> entityType;

    public RVP_DispenserWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data,
                               String serializeId, Supplier<EntityType<? extends Projectile>> entityType) {
        super(vehicle, unit, index, data, serializeId);
        this.entityType = entityType;
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isCoolingDown() || isReloading() || !consumeAmmo(aimContexts)) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        RVP_WeaponData data = getData();
        RVP_FireData fire = data.getFireData();
        float chargeScale = consumeChargeScale();
        int count = fire.getCanisterCount() * fire.getCanisterBurstCount();
        for (AimContext aim : aimContexts) {
            for (int i = 0; i < count; i++) {
                RVP_ProjectileSpawner.spawn(data, RVP_EnumWeaponKind.DISPENSER, entityType, getVehicle(), shooter, aim, null,
                        getWeaponUnit().getRootParentWeaponUnit(), chargeScale, fire.getCanisterDiff());
            }
            getVehicle().physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
        }
        return true;
    }
}
