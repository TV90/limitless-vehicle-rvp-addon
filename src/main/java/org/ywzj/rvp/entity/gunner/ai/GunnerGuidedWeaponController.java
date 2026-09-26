package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.behavior.runtime.RVP_GunnerObservationService;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

public final class GunnerGuidedWeaponController {

    /** HITL 导弹搜索范围（格），作为 O(实体) 遍历的距离闸门，与原 ±4096 立方体语义一致。 */
    private static final double HITL_CONTROL_SEARCH_RANGE = 4096.0D;

    private GunnerGuidedWeaponController() {}

    public static void tick(GunnerEntity gunner,
                            AbstractVehicle vehicle,
                            @Nullable WeaponUnit weaponUnit,
                            @Nullable Entity target,
                            RVP_GunnerObservationService observations) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        Vec3 targetPoint = targetPoint(target);
        updateDesignation(gunner, vehicle, weaponUnit, targetPoint, observations);
        updateGpsTarget(gunner, vehicle, weaponUnit, targetPoint);
        updateInFlightHitl(gunner, target, targetPoint, observations);
    }

    public static void prepareForLaunch(GunnerEntity gunner,
                                        AbstractVehicle vehicle,
                                        WeaponUnit weaponUnit,
                                        AbstractVehicleWeapon<?> rawWeapon,
                                        @Nullable Entity target) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        Vec3 targetPoint = targetPoint(target);
        if (targetPoint == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data == null) {
            return;
        }
        if (data.usesGuidanceType(RVP_EnumGuidanceType.GPS)) {
            GPSTargetManager.set(gunner, vehicle.level().dimension().location(), targetPoint);
        }
        if (needsDesignation(data)) {
            RVP_SaclosOperatorSession.setDesignation(gunner.getUUID(), true, targetPoint);
        }
    }

    private static void updateDesignation(GunnerEntity gunner,
                                          AbstractVehicle vehicle,
                                          @Nullable WeaponUnit weaponUnit,
                                          @Nullable Vec3 targetPoint,
                                          RVP_GunnerObservationService observations) {
        if (targetPoint == null || weaponUnit == null
                || !currentWeaponNeedsDesignation(gunner, weaponUnit)
                && !hasInFlightDesignationWeapon(observations)) {
            RVP_SaclosOperatorSession.setDesignation(gunner.getUUID(), false, null);
            return;
        }
        RVP_SaclosOperatorSession.setDesignation(gunner.getUUID(), true, targetPoint);
    }

    private static void updateGpsTarget(GunnerEntity gunner,
                                        AbstractVehicle vehicle,
                                        @Nullable WeaponUnit weaponUnit,
                                        @Nullable Vec3 targetPoint) {
        if (targetPoint == null || weaponUnit == null || !currentWeaponUses(weaponUnit, RVP_EnumGuidanceType.GPS)) {
            return;
        }
        GPSTargetManager.set(gunner, vehicle.level().dimension().location(), targetPoint);
    }

    private static void updateInFlightHitl(GunnerEntity gunner,
                                           @Nullable Entity target,
                                           @Nullable Vec3 targetPoint,
                                           RVP_GunnerObservationService observations) {
        if (targetPoint == null || target == null || !target.isAlive()) {
            return;
        }
        // 调用共享观察服务的 Owner 专用索引，避免 HITL 与照射维持各自遍历世界。
        for (RVP_BaseBullet projectile : observations.ownedProjectiles(HITL_CONTROL_SEARCH_RANGE)) {
            if (!(projectile instanceof RVP_MissileEntity missile)
                    || missile.getRvpData() == null
                    || !missile.getRvpData().hasHumanInTheLoop()
                    || !missile.rvp$isHitlActive()) {
                continue;
            }
            RVP_EnumHitlControlMode mode = missile.rvp$getHitlControlMode();
            if (mode == RVP_EnumHitlControlMode.DESIGNATE) {
                missile.rvp$setHitlDesignatedEntity(target);
            } else if (mode == RVP_EnumHitlControlMode.MOUSE) {
                Vec3 toTarget = targetPoint.subtract(missile.position());
                if (toTarget.lengthSqr() > 1.0E-6D) {
                    Vec2 rot = VectorUtil.vecToRot(toTarget);
                    missile.rvp$setHitlSteeringInput(rot.y, rot.x, gunner.tickCount);
                }
            }
        }
    }

    private static boolean currentWeaponNeedsDesignation(GunnerEntity gunner, WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = currentRvpWeapon(gunner, weaponUnit);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        return data != null && needsDesignation(data);
    }

    private static boolean needsDesignation(RVP_WeaponData data) {
        return data.isVehicleLaserGuided()
                || data.usesGuidanceType(RVP_EnumGuidanceType.HITL_TV);
    }

    private static boolean hasInFlightDesignationWeapon(RVP_GunnerObservationService observations) {
        // 调用共享观察服务的 Owner 专用索引，复用 HITL 维持已经请求的在途弹药集合。
        for (RVP_BaseBullet projectile : observations.ownedProjectiles(HITL_CONTROL_SEARCH_RANGE)) {
            if (projectile.getRvpData() != null && needsDesignation(projectile.getRvpData())) {
                return true;
            }
        }
        return false;
    }

    private static boolean currentWeaponUses(WeaponUnit weaponUnit, RVP_EnumGuidanceType type) {
        AbstractVehicleWeapon<?> weapon = currentRvpWeapon(null, weaponUnit);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        return data != null && data.usesGuidanceType(type);
    }

    @Nullable
    private static AbstractVehicleWeapon<?> currentRvpWeapon(@Nullable GunnerEntity gunner, WeaponUnit weaponUnit) {
        if (gunner != null) {
            int controlledIndex = gunner.getControlledWeaponIndex();
            if (controlledIndex >= 0 && controlledIndex < weaponUnit.getIndexedWeapons().size()) {
                AbstractVehicleWeapon<?> controlled = weaponUnit.proxyWeapon(
                        weaponUnit.getIndexedWeapons().get(controlledIndex));
                if (controlled != null) {
                    return controlled;
                }
            }
        }
        AbstractVehicleWeapon<?> current = weaponUnit.getCurrentWeapon().orElse(null);
        return current == null ? null : weaponUnit.proxyWeapon(current);
    }

    @Nullable
    private static Vec3 targetPoint(@Nullable Entity target) {
        if (target == null || !target.isAlive()) {
            return null;
        }
        return target.getBoundingBox().getCenter();
    }
}
