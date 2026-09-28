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

    /**
     * 当前 tick 的合格在途 SACLOS 弹状态。
     *
     * @param active 是否至少存在一枚仍符合发射站 STABLE PIP 资格的在途 SACLOS 弹
     * @param fallbackPoint 弹体最近可用的制导目标点，用于 Gunner 当前目标暂时丢失时续接会话
     */
    private record InFlightStableSaclosState(boolean active, @Nullable Vec3 fallbackPoint) {}

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
        // 调用本项目火控资格解析器，判断本次选中的 SACLOS 弹是否请求硬锁 PIP 辅助。
        boolean stablePipAssist = RVP_GunnerFireControlPolicy.supportsStableSaclos(weaponUnit, rawWeapon);
        if (needsDesignation(data) || stablePipAssist) {
            // 调用本项目服务端照射会话，写入 Gunner 目标点及经服务端复核的 STABLE PIP 请求。
            RVP_SaclosOperatorSession.setDesignation(
                    gunner.getUUID(), true, targetPoint, stablePipAssist);
        }
    }

    private static void updateDesignation(GunnerEntity gunner,
                                          AbstractVehicle vehicle,
                                          @Nullable WeaponUnit weaponUnit,
                                          @Nullable Vec3 targetPoint,
                                          RVP_GunnerObservationService observations) {
        // 调用共享观察快照，先于目标/当前武器门控检查仍在飞行的 STABLE SACLOS 弹。
        InFlightStableSaclosState inFlightStableSaclos = findInFlightStableSaclos(observations);
        if (targetPoint == null || weaponUnit == null) {
            if (inFlightStableSaclos.active()) {
                // 调用本项目操作手会话，目标短暂丢失时优先保留发射时的最后有效照射点。
                Vec3 retainedPoint = targetPoint;
                if (retainedPoint == null) {
                    // 调用本项目操作手会话，读取目标暂失前持续刷新的最后有效照射点。
                    retainedPoint = RVP_SaclosOperatorSession.getDesignationPoint(gunner.getUUID());
                }
                if (retainedPoint == null) {
                    retainedPoint = inFlightStableSaclos.fallbackPoint();
                }
                if (retainedPoint != null) {
                    // 调用本项目服务端会话，仅续期仍有合格在途弹的 STABLE PIP 请求。
                    RVP_SaclosOperatorSession.setDesignation(
                            gunner.getUUID(), true, retainedPoint, true);
                }
                // 活弹没有可恢复目标点时也不撤销已有会话；PIP 仍由新鲜度与雷达硬锁门控。
                return;
            }
            // 调用本项目操作手会话，目标丢失或武器站不可用时清除旧照射与稳定请求。
            RVP_SaclosOperatorSession.setDesignation(gunner.getUUID(), false, null);
            return;
        }
        // 调用本项目武器解析器，读取 Gunner 当前受控武器的 SACLOS STABLE 资格。
        boolean currentStableSaclos = RVP_GunnerFireControlPolicy.supportsStableSaclos(
                weaponUnit, currentRvpWeapon(gunner, weaponUnit));
        // 调用本项目现有制导会话扫描，保留激光照射与 HITL 在途维持行为。
        boolean inFlightDesignation = hasInFlightDesignationWeapon(observations);
        // 调用本项目当前受控武器识别器，检查是否仍需维持常规照射/在途控制。
        boolean currentNeedsDesignation = currentWeaponNeedsDesignation(gunner, weaponUnit);
        if (!currentNeedsDesignation
                && !inFlightDesignation
                && !currentStableSaclos
                && !inFlightStableSaclos.active()) {
            // 调用本项目操作手会话，目标或武器条件失效时清除旧照射与稳定请求。
            RVP_SaclosOperatorSession.setDesignation(gunner.getUUID(), false, null);
            return;
        }
        // 调用本项目服务端会话，每 tick 刷新目标点并仅在 Gunner SACLOS 资格有效时请求 PIP。
        RVP_SaclosOperatorSession.setDesignation(
                gunner.getUUID(), true, targetPoint, currentStableSaclos || inFlightStableSaclos.active());
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

    /** 收集仍有发射武器站 STABLE PIP 资格的在途 SACLOS 弹及可恢复目标点。 */
    private static InFlightStableSaclosState findInFlightStableSaclos(
            RVP_GunnerObservationService observations) {
        boolean active = false;
        Vec3 fallbackPoint = null;
        // 调用共享观察服务的全量 Owner 索引，避免长航程弹离开载具周围范围后停止续期。
        for (RVP_BaseBullet projectile : observations.ownedProjectiles()) {
            if (projectile.getRvpData() == null) {
                continue;
            }
            // 调用本项目火控策略，按弹体自身武器数据与发射武器站验证 PIP 资格。
            if (!RVP_GunnerFireControlPolicy.supportsStableSaclos(
                    projectile.getShooterWeaponUnit(), projectile.getRvpData())) {
                continue;
            }
            active = true;
            if (fallbackPoint == null) {
                // 调用本体弹药位置接口，目标跟踪暂失时优先复用弹体最近一次有效制导点。
                fallbackPoint = projectile.getLastGuidancePos();
                if (fallbackPoint == null) {
                    // 调用本体弹药目标点接口，兼容尚未写入 lastGuidancePos 的在途弹。
                    fallbackPoint = projectile.getTargetPos();
                }
            }
        }
        return new InFlightStableSaclosState(active, fallbackPoint);
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
