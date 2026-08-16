package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
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
                            @Nullable Entity target) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        Vec3 targetPoint = targetPoint(target);
        updateDesignation(gunner, vehicle, weaponUnit, targetPoint);
        updateGpsTarget(gunner, vehicle, weaponUnit, targetPoint);
        updateInFlightHitl(gunner, vehicle, target, targetPoint);
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
                                          @Nullable Vec3 targetPoint) {
        if (targetPoint == null || weaponUnit == null
                || !currentWeaponNeedsDesignation(gunner, weaponUnit)
                && !hasInFlightDesignationWeapon(gunner, vehicle)) {
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
                                           AbstractVehicle vehicle,
                                           @Nullable Entity target,
                                           @Nullable Vec3 targetPoint) {
        if (targetPoint == null || target == null || !target.isAlive()) {
            return;
        }
        if (!(vehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        // O(实体) 遍历已加载实体，替代 ±4096 立方体 getEntitiesOfClass（8192³，灾难级）；
        // 距离闸门还原原 box 范围，避免纳入 4096 格外的弹体
        AABB searchBox = vehicle.getBoundingBox().inflate(HITL_CONTROL_SEARCH_RANGE);
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (!(entity instanceof RVP_MissileEntity missile)
                    || !missile.isAlive()
                    || missile.getOwner() != gunner
                    || missile.getRvpData() == null
                    || !missile.getRvpData().hasHumanInTheLoop()
                    || !missile.rvp$isHitlActive()
                    || !missile.getBoundingBox().intersects(searchBox)) {
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

    private static boolean hasInFlightDesignationWeapon(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (!(vehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return false;
        }
        // O(实体) 遍历已加载实体，替代 ±4096 立方体 getEntitiesOfClass（8192³，灾难级）；
        // 距离闸门还原原 box 范围，避免把 4096 格外的弹判为空射依据
        AABB searchBox = vehicle.getBoundingBox().inflate(HITL_CONTROL_SEARCH_RANGE);
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (entity instanceof RVP_BaseBullet projectile
                    && projectile.isAlive()
                    && projectile.getOwner() == gunner
                    && projectile.getRvpData() != null
                    && needsDesignation(projectile.getRvpData())
                    && projectile.getBoundingBox().intersects(searchBox)) {
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
