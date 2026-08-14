package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceLaunchConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class GunnerWeaponSuitability {

    private GunnerWeaponSuitability() {}
    private static final Map<WeaponUnit, Map<Long, Integer>> ARM_PULSE_TICK_MAP = new WeakHashMap<>();

    public static boolean hasUsableWeaponForTarget(WeaponUnit rootUnit, Entity target) {
        for (AbstractVehicleWeapon<?> weapon : rootUnit.getIndexedWeapons()) {
            AbstractVehicleWeapon<?> proxyWeapon = rootUnit.proxyWeapon(weapon);
            if (proxyWeapon == null || proxyWeapon.getData() == null || proxyWeapon.getData().getWeaponId() == null) {
                continue;
            }
            if (canSelectForTarget(rootUnit, weapon, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断武器组内是否有可用的 GPS 武器能打击目标。
     * 供索敌阶段使用：启用 gps_prefer_farthest 时 gunner 优先用 GPS 导弹打击最远目标。
     *
     * @param rootUnit 载具武器组（内部会取代理武器）
     * @param target   候选目标
     * @return 存在有弹药、非冷却/装填、且能打击该目标的 GPS 武器时为 true
     */
    public static boolean hasUsableGpsWeaponForTarget(WeaponUnit rootUnit, Entity target) {
        for (AbstractVehicleWeapon<?> weapon : rootUnit.getIndexedWeapons()) {
            AbstractVehicleWeapon<?> proxyWeapon = rootUnit.proxyWeapon(weapon);
            if (proxyWeapon == null || proxyWeapon.getData() == null || proxyWeapon.getData().getWeaponId() == null) {
                continue;
            }
            // 可用性：有弹药且不在冷却/装填中
            if (!proxyWeapon.hasAmmo() || proxyWeapon.isCoolingDown() || proxyWeapon.isReloading()) {
                continue;
            }
            if (proxyWeapon instanceof RVP_WeaponBase rvpWeapon
                    && rvpWeapon.getData() != null
                    && rvpWeapon.getData().isGpsMissile()
                    && canSelectForTarget(rootUnit, weapon, target)) {
                return true;
            }
        }
        return false;
    }

    public static boolean canSelectForTarget(WeaponUnit rootUnit, AbstractVehicleWeapon<?> rawWeapon, Entity target) {
        AbstractVehicleWeapon<?> weapon = rootUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return true;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data == null || target == null || !target.isAlive()) {
            return false;
        }
        if (data.isGpsMissile() && isAirTarget(target)) {
            return false;
        }
        if ((data.isHomingProjectile() || data.usesGuidanceType(RVP_EnumGuidanceType.AIR))
                && !isAirTarget(target) && isAirOnlyMissile(data)) {
            return false;
        }
        if (data.isAntiRadiationMissile()) {
            return resolveArmPreselect(rootUnit, target, data, false);
        }
        if (usesGunnerControlSource(data)) {
            return canGuidanceReachTargetEnvelope(rvpWeapon.getWeaponUnit(), target, data);
        }
        if (data.isHomingProjectile()) {
            return canWeaponReachTargetEnvelope(rvpWeapon.getWeaponUnit(), target, data);
        }
        return true;
    }

    public static boolean prepareLaunchLock(WeaponUnit rootUnit, AbstractVehicleWeapon<?> rawWeapon, Entity target) {
        AbstractVehicleWeapon<?> weapon = rootUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return true;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data == null || target == null || !target.isAlive()) {
            return false;
        }
        if (data.isAntiRadiationMissile()) {
            return resolveArmPreselect(rootUnit, target, data, true);
        }
        if (!canSelectForTarget(rootUnit, rawWeapon, target)) {
            return false;
        }
        if (usesGunnerControlSource(data)) {
            return true;
        }
        // RF 制导武器必须有可用的雷达（载具自带或外置中继），否则不能发射；
        // 武器站传感器未写 rf 时，雷达制导（ARH/SARH）武器同样要求有可用雷达
        if (data.isHomingProjectile() && !data.isVehicleLaserGuided() && !data.isCommandGuided()) {
            WeaponUnit root = rootUnit.getRootParentWeaponUnit();
            boolean requiresRadar = root.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF
                    || data.isRadarHoming();
            if (requiresRadar && !hasUsableRadar(rootUnit, target)) {
                return false;
            }
        }
        if (!data.isRequireLock()) {
            if (data.isHomingProjectile() && canAcquireLockTarget(rvpWeapon.getWeaponUnit(), target, data)) {
                applyEntityLock(rootUnit, target);
            }
            return true;
        }
        if (!data.isHomingProjectile()) {
            return true;
        }
        if (!canAcquireLockTarget(rvpWeapon.getWeaponUnit(), target, data)) {
            return hasMatchingLock(rootUnit, rvpWeapon.getWeaponUnit(), target, data, false);
        }
        applyEntityLock(rootUnit, target);
        return hasMatchingLock(rootUnit, rvpWeapon.getWeaponUnit(), target, data, true);
    }

    private static boolean canWeaponReachTargetEnvelope(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        Vec3 origin = weaponUnit.worldPivotPosition();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double distance = origin.distanceTo(targetCenter);
        if (!contains(launch.targetDistanceRange(), distance)) {
            return false;
        }
        if (!contains(launch.altitudeRange(), altitudeAgl(target))) {
            return false;
        }
        return passesTargetMotionAngleGate(launch.angleGate(), distance, origin, target);
    }

    private static boolean canGuidanceReachTargetEnvelope(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return false;
        }
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(data, RVP_GuidancePhase.MAIN);
        Vec3 origin = weaponUnit.worldPivotPosition();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double distance = origin.distanceTo(targetCenter);
        double altitude = altitudeAgl(target);
        RVP_Range<Float> distanceRange = launch.targetDistanceRange() != null
                ? launch.targetDistanceRange()
                : active.targetDistanceRange();
        RVP_Range<Float> altitudeRange = launch.altitudeRange() != null
                ? launch.altitudeRange()
                : active.altitudeRange();
        Map<RVP_Range<Float>, RVP_Range<Float>> angleGate = launch.angleGate() != null
                ? launch.angleGate()
                : active.angleGate();
        if (!contains(distanceRange, distance)) {
            return false;
        }
        if (!contains(altitudeRange, altitude)) {
            return false;
        }
        return passesTargetMotionAngleGate(angleGate, distance, origin, target);
    }

    private static boolean canAcquireLockTarget(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        if (!canWeaponReachTargetEnvelope(weaponUnit, target, data)) {
            return false;
        }
        // 发射架/垂发车辆：跳过锁定锥角检查，导弹垂直发射后自行转向目标
        if (isLauncherVehicle(weaponUnit)) {
            return true;
        }
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        Vec3 origin = weaponUnit.worldPivotPosition();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        return withinAxisAngle(resolveLockAxis(weaponUnit), targetCenter.subtract(origin), launch.maxLockHalfAngle());
    }

    private static boolean canHoldLockTarget(WeaponUnit weaponUnit, Entity target, RVP_WeaponData data) {
        if (!canWeaponReachTargetEnvelope(weaponUnit, target, data)) {
            return false;
        }
        // 发射架/垂发车辆：跳过锁定保持锥角检查
        if (isLauncherVehicle(weaponUnit)) {
            return true;
        }
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        Vec3 origin = weaponUnit.worldPivotPosition();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        return withinAxisAngle(resolveLockAxis(weaponUnit), targetCenter.subtract(origin), launch.maxOffAxisLockAngle());
    }

    private static boolean hasMatchingLock(WeaponUnit rootUnit,
                                           WeaponUnit launchUnit,
                                           Entity target,
                                           RVP_WeaponData data,
                                           boolean allowFreshRfFallback) {
        WeaponUnit root = rootUnit.getRootParentWeaponUnit();
        Entity locked;
        if (isLauncherVehicle(launchUnit)) {
            // 发射架/垂发车辆：跳过严格 RF 锁定检查，直接用 root 的 locked entity
            locked = root.getLockedEntity();
        } else if (root.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
            locked = getStrictRfLockedEntity(root, target);
        } else {
            locked = root.getLockedEntity();
            if (locked == null && allowFreshRfFallback) {
                locked = root.getLockedEntity();
            }
        }
        return RVP_RadarRoleHelper.entityMatches(locked, target.getId())
                && canHoldLockTarget(launchUnit, target, data);
    }

    private static boolean isLauncherVehicle(WeaponUnit weaponUnit) {
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        return vehicle != null && GunnerBrain.hasLauncherDeployConfig(vehicle);
    }

    private static boolean resolveArmPreselect(WeaponUnit rootUnit,
                                               Entity target,
                                               RVP_WeaponData data,
                                               boolean writeSelection) {
        WeaponUnit root = rootUnit.getRootParentWeaponUnit();
        if (root == null) {
            return false;
        }
        Entity normalizedTarget = normalizeTarget(target);
        if (!(normalizedTarget instanceof AbstractVehicle targetVehicle) || !targetVehicle.isAlive()) {
            if (writeSelection) {
                RVP_WeaponLockStateTable.setArmPreselected(root, -1, -1, null);
            }
            return false;
        }

        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        Vec3 seekerPos = root.worldPivotPosition();
        Vec3 targetCenter = targetVehicle.getBoundingBox().getCenter();
        double targetDistance = seekerPos.distanceTo(targetCenter);
        if (!contains(launch.targetDistanceRange(), targetDistance)
                || !contains(launch.altitudeRange(), altitudeAgl(targetVehicle))) {
            if (writeSelection) {
                RVP_WeaponLockStateTable.setArmPreselected(root, -1, -1, null);
            }
            return false;
        }

        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(data, RVP_GuidancePhase.MAIN);
        float seekHalfAngle = Math.max(active.maxLockHalfAngle(), 0.5f);
        float seekRange = (float) RVP_GuidanceRuntimeGeometry.resolveScanRadius(active.targetDistanceRange());
        Vec3 seekerLook = resolveLockAxis(root);
        if (!withinAxisAngle(seekerLook, targetCenter.subtract(seekerPos), seekHalfAngle)) {
            if (writeSelection) {
                RVP_WeaponLockStateTable.setArmPreselected(root, -1, -1, null);
            }
            return false;
        }

        AntiRadiationSeekerHelper.AntiRadiationEmitter emitter = findBestTargetEmitter(
                root,
                targetVehicle,
                seekerPos,
                seekerLook,
                seekHalfAngle,
                seekRange,
                active.radiationPulseMemoryTick(),
                active.armLockedEmitterBonus()
        );
        if (emitter == null) {
            if (writeSelection) {
                RVP_WeaponLockStateTable.setArmPreselected(root, -1, -1, null);
            }
            return false;
        }

        if (writeSelection) {
            RVP_WeaponLockStateTable.setArmPreselected(root, emitter.vehicleId(), emitter.radarIndex(), emitter.position());
        }
        return true;
    }

    @Nullable
    private static AntiRadiationSeekerHelper.AntiRadiationEmitter findBestTargetEmitter(WeaponUnit root,
                                                                                       AbstractVehicle targetVehicle,
                                                                                       Vec3 seekerPos,
                                                                                       Vec3 seekerLook,
                                                                                       float seekHalfAngle,
                                                                                       float seekRange,
                                                                                       int pulseMemoryTick,
                                                                                       float lockedBonus) {
        Map<Long, Integer> pulseTicks = ARM_PULSE_TICK_MAP.computeIfAbsent(root, ignored -> new HashMap<>());
        List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters =
                AntiRadiationSeekerHelper.scanVisibleEmitters(
                        root.getVehicle().level(),
                        seekerPos,
                        seekerLook,
                        seekHalfAngle,
                        seekRange,
                        root.getVehicle(),
                        root.getVehicle().tickCount,
                        pulseTicks,
                        pulseMemoryTick
                );
        AntiRadiationSeekerHelper.AntiRadiationEmitter best = null;
        double bestScore = Double.MAX_VALUE;
        for (AntiRadiationSeekerHelper.AntiRadiationEmitter emitter : emitters) {
            if (emitter.vehicleId() != targetVehicle.getId()) {
                continue;
            }
            double score = AntiRadiationSeekerHelper.score(
                    seekerPos,
                    seekerLook,
                    seekHalfAngle,
                    seekRange,
                    emitter.pdw(),
                    lockedBonus
            );
            if (score < bestScore) {
                bestScore = score;
                best = emitter;
            }
        }
        return best;
    }

    @Nullable
    private static Entity getStrictRfLockedEntity(WeaponUnit root, Entity target) {
        RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(root);
        if (radar != null && RVP_RadarRoleHelper.entityMatches(radar.getLockedEntity(), target.getId())) {
            return radar.getLockedEntity();
        }
        if (root != null
                && RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root) == target.getId()) {
            return target;
        }
        return null;
    }

    private static void applyEntityLock(WeaponUnit rootUnit, Entity target) {
        WeaponUnit root = rootUnit.getRootParentWeaponUnit();
        if (root.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
            RVP_RadarRoleHelper.applyRequestedLock(root, target);
            // 直接设置雷达锁定，不依赖雷达是否已探测到目标
            // 确保 FRIENDLY/TEAM gunner（不经过 tickRadarLock）也能让雷达锁定
            for (RadarUnit radar : root.getRadarUnits()) {
                if (radar.isOn() && RVP_RadarRoleHelper.canLock(radar)) {
                    radar.detect(target);
                    if (radar.getLockedEntity() != target) {
                        radar.setLockedEntity(target);
                    }
                }
            }
        }
        if (!RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), target.getId())) {
            root.setLockedEntity(target);
        }
    }

    /**
     * 检查是否有可用的雷达锁定目标（载具自带雷达或外置中继雷达）
     */
    private static boolean hasUsableRadar(WeaponUnit weaponUnit, Entity target) {
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        for (RadarUnit radar : root.getRadarUnits()) {
            if (radar.isOn() && RVP_RadarRoleHelper.canLock(radar)) {
                return true;
            }
        }
        AbstractVehicle vehicle = root.getVehicle();
        if (vehicle == null) {
            return false;
        }
        return RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(vehicle)
                .map(relay -> RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relay) != null)
                .orElse(false);
    }

    @Nullable
    private static Entity normalizeTarget(@Nullable Entity target) {
        if (target instanceof net.minecraft.world.entity.player.Player player
                && player.getVehicle() instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        return target;
    }

    private static Vec3 resolveLockAxis(WeaponUnit weaponUnit) {
        Vec3 axis = weaponUnit.worldVec();
        if (axis.lengthSqr() > 1.0E-6) {
            return axis.normalize();
        }
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        Vec3 vehicleLook = vehicle.getLookAngle();
        return vehicleLook.lengthSqr() > 1.0E-6 ? vehicleLook.normalize() : Vec3.ZERO;
    }

    private static boolean passesTargetMotionAngleGate(@Nullable Map<RVP_Range<Float>, RVP_Range<Float>> angleGate,
                                                       double distance,
                                                       Vec3 origin,
                                                       Entity target) {
        if (angleGate == null || angleGate.isEmpty()) {
            return true;
        }
        RVP_Range<Float> allowedAngle = null;
        for (Map.Entry<RVP_Range<Float>, RVP_Range<Float>> entry : angleGate.entrySet()) {
            RVP_Range<Float> distanceRange = entry.getKey();
            if (distanceRange != null && distanceRange.contains((float) distance)) {
                allowedAngle = entry.getValue();
                break;
            }
        }
        if (allowedAngle == null) {
            return true;
        }
        Vec3 velocity = target.getDeltaMovement();
        if (velocity.lengthSqr() <= 1.0E-6) {
            return true;
        }
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(origin);
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }
        double angle = angleBetween(velocity, toTarget);
        return Double.isNaN(angle) || allowedAngle.contains((float) angle);
    }

    private static boolean withinAxisAngle(Vec3 axis, Vec3 toTarget, double maxAngle) {
        if (axis == null || toTarget == null || axis.lengthSqr() <= 1.0E-8 || toTarget.lengthSqr() <= 1.0E-8) {
            return true;
        }
        return angleBetween(axis, toTarget) <= Math.max(maxAngle, 0.0) + 1.0E-6;
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        double dot = Mth.clamp(a.normalize().dot(b.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private static boolean contains(@Nullable RVP_Range<Float> range, double value) {
        return range == null || range.contains((float) value);
    }

    private static boolean usesGunnerControlSource(RVP_WeaponData data) {
        return data.usesGuidanceType(RVP_EnumGuidanceType.GPS)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SALH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LBR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)
                || data.usesGuidanceType(RVP_EnumGuidanceType.HITL_TV)
                || data.usesGuidanceType(RVP_EnumGuidanceType.HITL_CLOS_TV);
    }

    private static double altitudeAgl(Entity target) {
        int groundY = target.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(target.getX()),
                Mth.floor(target.getZ()));
        return target.getY() - groundY;
    }

    private static boolean isAirTarget(Entity target) {
        return altitudeAgl(target) > 25.0;
    }

    /**
     * 判断导弹是否为纯对空导弹
     * 有lock_altitude_range时：下限不为-inf即为对空
     * 没有lock_altitude_range时：ARH/SARH/AIR/IR默认视为对空导弹
     */
    private static boolean isAirOnlyMissile(RVP_WeaponData data) {
        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(data);
        if (launch == null || launch.altitudeRange() == null) {
            return data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                    || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                    || data.usesGuidanceType(RVP_EnumGuidanceType.AIR)
                    || data.usesGuidanceType(RVP_EnumGuidanceType.IR);
        }
        List<RVP_Range.Interval<Float>> intervals = launch.altitudeRange().intervals();
        return !intervals.isEmpty() && intervals.get(0).lower() != null;
    }
}
