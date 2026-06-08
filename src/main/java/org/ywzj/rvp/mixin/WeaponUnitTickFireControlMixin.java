package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.List;
import java.util.Optional;

/**
 * Vanilla lock validation can clear RVP cockpit locks; RVP seeker acquire is handled separately.
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitTickFireControlMixin {

    @Redirect(
            method = "tickFireControl",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/weapon/seeker/Infrared;checkTarget(Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/entity/Entity;"
            ),
            remap = false
    )
    private Entity ywzj_rvp$redirectIrLockCheck(WeaponUnit weaponUnit, Entity target) {
        if (ywzj_rvp$delegatesSeekerToRvp(weaponUnit)) {
            return target;
        }
        return Infrared.checkTarget(weaponUnit, target);
    }

    @Redirect(
            method = "tickFireControl",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/weapon/seeker/Radar;checkTarget(Lnet/minecraft/world/entity/Entity;Ljava/util/List;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/entity/Entity;"
            ),
            remap = false
    )
    private Entity ywzj_rvp$redirectRfLockCheck(Entity fromEntity, List<Entity> entities, Entity target) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (ywzj_rvp$delegatesSeekerToRvp(self)) {
            return target;
        }
        return Radar.checkTarget(fromEntity, entities, target);
    }

    private static boolean ywzj_rvp$delegatesSeekerToRvp(WeaponUnit unit) {
        Optional<AbstractVehicleWeapon<?>> weapon = unit.getCurrentWeapon();
        if (weapon.isEmpty() || !(weapon.get() instanceof RVP_WeaponBase rvp)) {
            return false;
        }
        return RVP_SeekerWeaponUtil.preLaunchSeekerHudActive(rvp.getData());
    }
}
