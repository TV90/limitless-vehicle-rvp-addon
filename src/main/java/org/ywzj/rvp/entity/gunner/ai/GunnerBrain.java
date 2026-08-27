package org.ywzj.rvp.entity.gunner.ai;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLPaths;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;
import org.ywzj.rvp.countermeasure.server.RVP_CountermeasureRuntimeManager;
import org.ywzj.rvp.ecm.RVP_EcmActiveManager;
import org.ywzj.rvp.vehicle.BoneEcmActiveConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle.Seat;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class GunnerBrain {

    private GunnerBrain() {}
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int AIR_PHASE_ATTACK = 0;
    private static final int AIR_PHASE_DISENGAGE = 1;
    private static final int GROUND_TACTICAL_HOLD_TICK = 100;
    private static final int GROUND_TACTICAL_EVADE_MIN_TICK = 140;
    private static final int GROUND_TACTICAL_EVADE_MAX_TICK = 280;
    /** 红外威胁检测半径（格）：扫描跟踪本载具的红外族导弹。 */
    private static final double INFRARED_THREAT_RADIUS = 200.0;
    /** 近距敌方导弹告警半径（格）：任何敌对阵营导弹进入该圈即抛烟规避。 */
    private static final double NEARBY_MISSILE_RADIUS = 100.0;
    /** 来袭角判定阈值（度）：导弹速度方向与"导弹→本车"方向夹角小于该值才视为正向本车袭来。 */
    private static final double INBOUND_MISSILE_ANGLE_DEG = 60.0;
    /** 来袭判定的最小导弹速度（格/tick）：低于此值（刚冷发射/加速段）暂不判为来袭。 */
    private static final double INBOUND_MIN_SPEED = 0.1;
    /** 被激光照射判定半径（格）：敌对玩家照射点落入本车包围盒该膨胀范围内视为正在照射本车。 */
    private static final double LASER_SPOT_RADIUS = 8.0;
    /** 激光照射状态新鲜度（毫秒）：客户端每 tick 同步照射点，超时视为已停止照射。 */
    private static final long LASER_FRESH_MS = 1000;
    /** 烟雾躲避停车时长（tick）：略大于烟雾存活（默认 240）。 */
    private static final int SMOKE_HOLD_TICKS = 260;
    /** 烟雾查找半径（格）：寻找最近的烟雾云开进并停车。 */
    private static final double SMOKE_LOOK_RADIUS = 48.0;
    private static final double FIXEDWING_ATTACK_ENTRY_MIN_AGL = 175.0;
    private static final double FIXEDWING_INITIAL_DISENGAGE_SCALE = 0.45;
    private static final double FIXEDWING_DISENGAGE_SCALE = 0.55;
    private static final double FIXEDWING_ATTACK_SCALE = 1.4;
    private static final double ROTARY_INITIAL_DISENGAGE_SCALE = 0.2;
    private static final double ROTARY_DISENGAGE_SCALE = 0.35;
    private static final double ROTARY_ATTACK_SCALE = 1.15;
    private static final Map<AbstractVehicleWeapon<?>, Long> DRIVER_AMMO_READY_TIME = new WeakHashMap<>();
    /** 所有 RVP 制导武器发射后的统一冷却（tick）：5 秒。 */
    private static final int MISSILE_COOLDOWN_TICK = 100;
    /** SEAD 复仇模式：未激活。 */
    private static final int SEAD_NONE = 0;
    /** SEAD 复仇阶段：飞离（背对锁定者拉开距离）。 */
    private static final int SEAD_FLY_AWAY = 1;
    /** SEAD 复仇阶段：回旋（转向对准锁定者并持续检查攻击门控）。 */
    private static final int SEAD_REVERSAL = 2;
    /** SEAD 复仇阶段：锁定并发射（保持对准，门控通过即射）。 */
    private static final int SEAD_LOCK_FIRE = 3;
    /** SEAD 飞离阶段时长（tick）：5 秒，给转向+脱离留出机动余量（过长会长时间不作战）。 */
    private static final int SEAD_FLY_AWAY_TICK = 100;
    /** SEAD 回旋阶段时长上限（tick）：8 秒，门控通过即提前进入发射。 */
    private static final int SEAD_REVERSAL_TICK = 160;
    /** SEAD 锁定发射阶段时长上限（tick）：2 秒，防止永远进不了门控。 */
    private static final int SEAD_LOCK_FIRE_TICK = 40;
    /** SEAD 复仇总超时（tick）：20 秒保险上限，防 gunner 卡死追着不放。 */
    private static final int SEAD_TIMEOUT_TICK = 400;
    /** SEAD 复仇结束后冷却（tick）：20 秒内不重新触发，防止被持续锁定时无限"飞离→复仇→再飞离"。 */
    private static final int SEAD_COOLDOWN_TICK = 400;
    /** SEAD 雷达锁定威胁检测节流（tick）：每 0.5 秒扫一次，避免逐 tick 全量遍历。 */
    private static final int SEAD_THREAT_SCAN_INTERVAL = 10;
    /** SEAD 雷达锁定检测半径（格）：扫描该范围内锁定本机的敌方雷达载具。 */
    private static final double SEAD_RADAR_LOCK_RANGE = 1024.0;

    public static void tick(GunnerEntity gunner, AbstractVehicle vehicle) {
        gunner.tickCooldowns();
        GunnerProfile profile = GunnerProfileManager.INSTANCE.getProfile(
                GunnerProfileManager.INSTANCE.normalizeProfileId(gunner.getProfileId())
        );
        PartUnit<?> seatUnit = vehicle.getOwnOperatorUnit(gunner);
        boolean driver = isDriver(vehicle, gunner);
        WeaponUnit weaponUnit = resolveWeaponUnit(vehicle, seatUnit, driver);
        Entity target = tickTargeting(gunner, vehicle, weaponUnit, profile);

        boolean driverAi = driver && profile.isAllowDrive();
        if (driverAi) {
            refillDriverVehicle(gunner, vehicle);
            sustainDriverInfiniteAmmo(vehicle);
        } else {
            clearDriverInfiniteAmmoTimers(vehicle);
            if (vehicle.getDriver() == gunner) {
                vehicle.controlUnit.reset();
            }
            gunner.clearDriverRideState();
        }

        tickCountermeasure(gunner, vehicle, profile);
        tickEcmActive(gunner, vehicle);
        // 地面载具被红外导弹锁定：抛烟雾并开进烟雾停车（仅司机 AI）
        if (driverAi) {
            tickSmokeEvasion(gunner, vehicle);
        }
        tickRadarLock(gunner, vehicle, weaponUnit, target, profile);
        GunnerExternalRadarController.tick(gunner, vehicle, weaponUnit, target, driverAi);
        GunnerGuidedWeaponController.tick(gunner, vehicle, weaponUnit, target);

        boolean allowFire = true;
        // SEAD 复仇：被雷达锁定且带反辐射弹时，gunner 自行驾驶飞机完成"逃→回头→锁&打"。
        // 返回 true 表示本 tick 已由 SEAD 接管（含驾驶与复仇开火），跳过常规 driving/combat。
        boolean seadHandled = false;
        if (driverAi) {
            seadHandled = tickSead(gunner, vehicle, weaponUnit, profile);
            if (!seadHandled) {
                allowFire = tickDriving(gunner, vehicle, target, profile);
            }
        }
        if (!seadHandled) {
            if (weaponUnit != null && target != null && allowFire) {
                tickCombat(gunner, weaponUnit, target, profile);
            } else {
                gunner.setControlledWeaponIndex(-1);
            }
        }

        // 周期监控（仅客户端有效）。必须按 dist 隔离调用：该类引用了 Minecraft/LocalPlayer 等
        // 客户端专属类，服务端若加载该类会在类加载验证阶段连带解析这些类并被 RuntimeDistCleaner 拦截崩溃。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RVP_GunnerDebugMonitor.onTick(gunner, vehicle, weaponUnit, target);
        }
    }

    private static void tickRadarLock(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable WeaponUnit weaponUnit, @Nullable Entity target, GunnerProfile profile) {
        if (weaponUnit == null || target == null || !target.isAlive()) {
            return;
        }
        // 武器站传感器未写 rf 时，只要武器组内有能打击该目标的雷达制导武器（ARH/SARH）也应雷达锁定，
        // 否则枪手导弹无实体锁 → 无 targetEntity → 干扰检测不生效
        if (weaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF
                && !hasRadarHomingWeaponForTarget(weaponUnit, target)) {
            return;
        }
        RadarUnit radar = prepareGunnerLockRadar(weaponUnit);
        if (radar == null) {
            return;
        }

        Entity lockTarget = null;
        if (target instanceof Player player) {
            if (player.getVehicle() instanceof AbstractVehicle targetVehicle) {
                lockTarget = targetVehicle;
            } else {
                lockTarget = player;
            }
        } else if (target instanceof AbstractVehicle targetVehicle) {
            lockTarget = targetVehicle;
        }

        if (lockTarget == null || !lockTarget.isAlive()) {
            clearGunnerRadarLock(weaponUnit, radar);
            return;
        }

        double maxRange = radar.getMaxScanDistance();
        if (radar.worldRadarPosition().distanceToSqr(lockTarget.position()) > maxRange * maxRange) {
            clearGunnerRadarLock(weaponUnit, radar);
            return;
        }

        Vec2 aimRot = radar.aimRot(lockTarget.position());
        if (aimRot.y < radar.getYRotMin() || aimRot.y > radar.getYRotMax()) {
            clearGunnerRadarLock(weaponUnit, radar);
            return;
        }
        if (aimRot.x < radar.getXRotMin() || aimRot.x > radar.getXRotMax()) {
            clearGunnerRadarLock(weaponUnit, radar);
            return;
        }

        radar.detect(lockTarget);
        // 箔条禁锁期：目标被箔条干扰脱锁后短时间内不可被选中/锁定（仍可被扫描），
        // 否则炮手 AI 每 tick 重锁会令脱锁瞬间被还原，雷达看起来"怎么都脱不了锁"
        if (RVP_ChaffJamState.isInCooldown(lockTarget.getUUID(), vehicle.level().getGameTime())) {
            clearGunnerRadarLock(weaponUnit, radar);
            return;
        }
        if (radar.getLockedEntity() != lockTarget) {
            radar.setLockedEntity(lockTarget);
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getLockedEntity() != lockTarget) {
            root.setLockedEntity(lockTarget);
        }
    }

    @Nullable
    private static RadarUnit prepareGunnerLockRadar(WeaponUnit weaponUnit) {
        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
            if (!radarUnit.isOn()) {
                radarUnit.toggle(true);
            }
        }
        return RVP_RadarRoleHelper.getPreferredLockRadar(weaponUnit);
    }

    private static void clearGunnerRadarLock(WeaponUnit weaponUnit, RadarUnit radar) {
        if (radar.getLockedEntity() != null) {
            radar.setLockedEntity(null);
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getLockedEntity() != null) {
            root.setLockedEntity(null);
        }
    }

    /** 武器组内是否有能打击目标的雷达制导（ARH/SARH）武器（有则枪手应雷达锁定目标）。 */
    private static boolean hasRadarHomingWeaponForTarget(WeaponUnit weaponUnit, Entity target) {
        for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
            AbstractVehicleWeapon<?> proxyWeapon = weaponUnit.proxyWeapon(weapon);
            if (!(proxyWeapon instanceof RVP_WeaponBase rvpWeapon)) {
                continue;
            }
            RVP_WeaponData data = rvpWeapon.getData();
            if (data == null || !data.isRadarHoming()) {
                continue;
            }
            if (GunnerWeaponSuitability.canSelectForTarget(weaponUnit, weapon, target)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Entity tickTargeting(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable WeaponUnit weaponUnit, GunnerProfile profile) {
        if (weaponUnit == null) {
            gunner.setTrackedTarget(null);
            return null;
        }

        // CIWS: prioritize intercepting missiles/bombs
        AmmoEntity ciwsTarget = GunnerTargeting.findCiwsTarget(gunner, vehicle);
        if (ciwsTarget != null) {
            gunner.setTrackedTarget(ciwsTarget);
            return ciwsTarget;
        }

        if (gunner.tickCount % profile.getScanIntervalTick() == 0) {
            gunner.setTrackedTarget(GunnerTargeting.findBestTarget(gunner, vehicle, weaponUnit, profile));
        }
        Entity tracked = gunner.getTrackedTarget();
        if (tracked == null || !tracked.isAlive()) {
            gunner.setTrackedTarget(null);
            return null;
        }
        return tracked;
    }

    private static boolean isCiwsAltitudeMet(AbstractVehicle vehicle) {
        double agl = vehicle.getY() - vehicle.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.util.Mth.floor(vehicle.getX()),
                net.minecraft.util.Mth.floor(vehicle.getZ()));
        return agl >= 50.0;
    }

    private static void tickCombat(GunnerEntity gunner, WeaponUnit weaponUnit, Entity target, GunnerProfile profile) {
        AbstractVehicle veh = weaponUnit.getVehicle();
        boolean launcher = veh != null && hasLauncherDeployConfig(veh);
        if (!launcher) {
            Vec3 aimPoint = GunnerTargeting.predictAimPoint(weaponUnit.worldPivotPosition(), target)
                    .add(target.getDeltaMovement().scale(Math.max(0.0, profile.getLeadScale() - 1.0)));
            weaponUnit.aim(aimPoint);
        }

        int weaponIndex = selectWeaponIndex(weaponUnit, target, profile);
        if (weaponIndex < 0) {
            gunner.setControlledWeaponIndex(-1);
            return;
        }
        gunner.setControlledWeaponIndex(weaponIndex);

        AbstractVehicleWeapon<?> selectedWeapon = weaponUnit.getIndexedWeapons().get(weaponIndex);
        boolean isRvpMissile = isRvpHomingMissile(weaponUnit, selectedWeapon);
        boolean isSelfGuided = isSelfGuidedMissile(weaponUnit, selectedWeapon);

        // 对空导弹纪律：目标是飞机时，必须持锁满 5 秒（100 tick）才能发射；
        // 发射后 5 秒内不再对同一目标发射（目标切换时计时重置）
        boolean isAircraftTarget = target instanceof FixedWingVehicle || target instanceof RotaryWingVehicle;
        if (isRvpMissile && isAircraftTarget) {
            int now = gunner.tickCount;
            if (gunner.getAirLockStartTick() == 0) {
                gunner.setAirLockStartTick(now);
            }
            if (now - gunner.getAirLockStartTick() < 100) {
                return;
            }
            if (gunner.getLastAirMissileFireTick() != 0 && now - gunner.getLastAirMissileFireTick() < 100) {
                return;
            }
        }

        if (isRvpMissile && gunner.getMissileCooldown() > 0) {
            return;
        }

        if (!gunner.isBurstWindowOpen()) {
            return;
        }

        // 炮塔对准窗口：仅对非制导武器（机炮等）要求对准。RVP 制导武器发射后自行转向目标，
        // 不受炮塔旋转角度限制（垂发车辆本就跳过该检查）。
        if (!launcher && !isRvpMissile) {
            float xErr = Math.abs(Mth.wrapDegrees(weaponUnit.getXRot() - weaponUnit.getXAimRot()));
            float yErr = Math.abs(Mth.wrapDegrees(weaponUnit.getYRot() - weaponUnit.getYAimRot()));
            if (!(xErr <= profile.getFireWindowDeg() && yErr <= profile.getFireWindowDeg())) {
                return;
            }
        }
        if (!GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, selectedWeapon, target)) {
            // 制导武器无法发射（如缺雷达/锁不上/锥角不足）时回退到机炮等非制导武器，
            // 避免 gunner 卡死在"选中导弹但打不出"而不作战（回归：优先导弹后必须保留机炮兜底）
            if (isRvpMissile) {
                int fallback = findGunWeaponIndex(weaponUnit, target);
                if (fallback < 0 || fallback == weaponIndex) {
                    return;
                }
                selectedWeapon = weaponUnit.getIndexedWeapons().get(fallback);
                weaponIndex = fallback;
                gunner.setControlledWeaponIndex(fallback);
                if (!GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, selectedWeapon, target)) {
                    return;
                }
            } else {
                return;
            }
        }
        GunnerGuidedWeaponController.prepareForLaunch(gunner, weaponUnit.getVehicle(), weaponUnit, selectedWeapon, target);
        // 使用实际发射武器站的 aimContexts（对 VehicleWeaponAgent 而言是目标武器站，如 launcher_weapon）
        WeaponUnit aimSource = selectedWeapon.getWeaponUnit();
        // 与手动发射保持一致：RIPPLE（轮射）只传当前管位 1 个瞄准上下文，SALVO（齐射）才一次全部发射。
        // 此前非垂发车辆一律传 aimSource.aimContexts()，多管发射架（如 cssa5/ps1sm 的 missile_barrel 有 2 根管）
        // 会一次把多管全打出去；垂发车辆也保留单上下文特判。
        boolean singleContext = launcher || aimSource.getFiringMode() == WeaponUnitData.FiringMode.RIPPLE;
        weaponUnit.shoot(weaponIndex, singleContext ? Collections.singletonList(aimSource.aimContext()) : aimSource.aimContexts(), gunner);
        gunner.onBurstShot(launcher ? 1 : profile.getBurstFireTick(), profile.getBurstRestTick());
        if (isRvpMissile) {
            gunner.setMissileCooldown(MISSILE_COOLDOWN_TICK);
            if (isAircraftTarget) {
                gunner.setLastAirMissileFireTick(gunner.tickCount);
            }
        }
        if (isSelfGuided && target instanceof AmmoEntity) {
            gunner.setCiwsTargetCooldown(target, 100);
        }
    }

    /**
     * 判断是否为 RVP 导弹类武器（包括所有具备防空拦截能力的制导类型）。
     * 用于施加 60tick 射速限制以及在 missile/gun 分类中使用。
     */
    private static boolean isRvpHomingMissile(WeaponUnit weaponUnit, AbstractVehicleWeapon<?> rawWeapon) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data == null) {
            return false;
        }
        return data.isHomingProjectile()
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.AIR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SALH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LBR);
    }

    /**
     * 判断是否为"射后不管/半射后不管"的自导导弹（IR/ARH/SARH/AIR）。
     * 这类导弹发射后不需要 gunner 持续瞄准目标，gunner 可以立即转向下一发来袭弹药。
     */
    private static boolean isSelfGuidedMissile(WeaponUnit weaponUnit, AbstractVehicleWeapon<?> rawWeapon) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data == null) {
            return false;
        }
        return data.isHomingProjectile()
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.AIR);
    }

    private static boolean tickDriving(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        vehicle.controlUnit.reset();
        boolean allowFire = true;
        if (vehicle instanceof FixedWingVehicle fixedWingVehicle) {
            allowFire = tickFixedWingDriving(gunner, fixedWingVehicle, target, profile);
            return allowFire;
        }
        if (vehicle instanceof RotaryWingVehicle rotaryWingVehicle) {
            allowFire = tickRotaryDriving(gunner, rotaryWingVehicle, target, profile);
            return allowFire;
        }
        if (hasLauncherDeployConfig(vehicle)) {
            tickLauncherGroundDriving(gunner, vehicle, target, profile);
            return true;
        }
        tickGroundDriving(gunner, vehicle, target, profile);
        return allowFire;
    }

    /** 检查载具 JSON 是否有发射架部署配置。 */
    public static boolean hasLauncherDeployConfig(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.getVehicleId() == null) {
            return false;
        }
        List<RVP_LauncherDeployConfig> configs = RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId());
        return !configs.isEmpty();
    }

    /** Launcher vehicle driving: park when has ammo, roam when reloading. */
    private static void tickLauncherGroundDriving(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        boolean hasAmmo = hasAnyAmmo(vehicle);
        if (hasAmmo) {
            // Park — stop and let weapon system aim freely
            gunner.clearTacticalEvade();
            vehicle.controlUnit.reset();
            return;
        }
        // No ammo — reloading, roam tactically
        if (target == null) {
            tickGroundWander(gunner, vehicle, profile);
            return;
        }
        Vec3 delta = target.position().subtract(vehicle.position());
        double distSqr = delta.horizontalDistanceSqr();
        double dist = Math.sqrt(distSqr);
        Vec2 targetRot = VectorUtil.vecToRot(new Vec3(delta.x, 0, delta.z));
        float yawDelta = Mth.wrapDegrees(targetRot.y - vehicle.getYRot());
        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        boolean desireMove = dist > stopDist * 1.6 && Math.abs(yawDelta) < 25.0f;
        boolean tacticalTarget = stopDist > 0.0;

        if (tacticalTarget && dist <= stopDist) {
            if (!gunner.hasTacticalHoldTicks() && !gunner.hasTacticalEvadeTicks()) {
                gunner.startTacticalHold(GROUND_TACTICAL_HOLD_TICK);
                gunner.startTacticalEvade(GROUND_TACTICAL_EVADE_MIN_TICK
                        + gunner.getRandom().nextInt(GROUND_TACTICAL_EVADE_MAX_TICK - GROUND_TACTICAL_EVADE_MIN_TICK + 1), 55.0F);
            }
        } else if (!tacticalTarget || dist > stopDist * 1.8) {
            gunner.clearTacticalEvade();
        }

        if (gunner.tickCount % profile.getDriveStuckCheckTick() == 0 && gunner.getRecoveryCooldownTicks() <= 0) {
            double moved = vehicle.position().distanceToSqr(gunner.getLastDriveCheckX(), vehicle.getY(), gunner.getLastDriveCheckZ());
            double stuckDist = profile.getDriveStuckDistance();
            if (desireMove && moved < stuckDist * stuckDist) {
                gunner.startRecovery(profile.getDriveRecoveryTick());
                gunner.setRecoveryCooldownTicks(profile.getDriveRecoveryTick() * 2 + profile.getDriveStuckCheckTick());
            }
            gunner.setLastDriveCheck(vehicle.getX(), vehicle.getZ());
        }

        if (gunner.hasRecoveryTicks()) {
            vehicle.controlUnit.backward = true;
            if (yawDelta > 0) vehicle.controlUnit.right = true;
            else vehicle.controlUnit.left = true;
            return;
        }
        if (gunner.hasTacticalHoldTicks()) return;
        if (gunner.hasTacticalEvadeTicks()) {
            tickGroundTacticalEvade(gunner, vehicle, target, yawDelta);
            return;
        }

        if (yawDelta > 8) vehicle.controlUnit.right = true;
        else if (yawDelta < -8) vehicle.controlUnit.left = true;
        if (dist > stopDist * 1.2 && Math.abs(yawDelta) < 80) vehicle.controlUnit.forward = true;
    }

    private static boolean hasAnyAmmo(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) continue;
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                AbstractVehicleWeapon<?> proxy = weaponUnit.proxyWeapon(weapon);
                boolean countermeasure = isCountermeasureWeapon(proxy);
                boolean hasAmmo = proxy.hasAmmo();
                if (!countermeasure && hasAmmo) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void tickGroundDriving(GunnerEntity gunner, AbstractVehicle vehicle, Entity target, GunnerProfile profile) {
        // 烟雾躲避停车：开进烟雾停车直到烟雾消散（优先于正常驾驶）
        if (gunner.hasSmokeHoldTicks()) {
            tickSmokeHoldDrive(gunner, vehicle);
            return;
        }
        if (target == null) {
            gunner.clearTacticalEvade();
            tickGroundWander(gunner, vehicle, profile);
            return;
        }
        Vec3 delta = target.position().subtract(vehicle.position());
        double distSqr = delta.horizontalDistanceSqr();
        double dist = Math.sqrt(distSqr);
        Vec2 targetRot = VectorUtil.vecToRot(new Vec3(delta.x, 0, delta.z));
        float yawDelta = Mth.wrapDegrees(targetRot.y - vehicle.getYRot());

        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        boolean desireMove = dist > stopDist * 1.6 && Math.abs(yawDelta) < 25.0f;
        boolean tacticalTarget = stopDist > 0.0;

        if (tacticalTarget && dist <= stopDist) {
            if (!gunner.hasTacticalHoldTicks() && !gunner.hasTacticalEvadeTicks()) {
                float yawBias = gunner.getRandom().nextBoolean() ? 55.0F : -55.0F;
                gunner.startTacticalHold(GROUND_TACTICAL_HOLD_TICK);
                gunner.startTacticalEvade(GROUND_TACTICAL_EVADE_MIN_TICK
                        + gunner.getRandom().nextInt(GROUND_TACTICAL_EVADE_MAX_TICK - GROUND_TACTICAL_EVADE_MIN_TICK + 1), yawBias);
            }
        } else if (!tacticalTarget || dist > stopDist * 1.8) {
            gunner.clearTacticalEvade();
        }

        if (gunner.tickCount % profile.getDriveStuckCheckTick() == 0 && gunner.getRecoveryCooldownTicks() <= 0) {
            double moved = vehicle.position().distanceToSqr(gunner.getLastDriveCheckX(), vehicle.getY(), gunner.getLastDriveCheckZ());
            double stuckDist = profile.getDriveStuckDistance();
            if (desireMove && moved < stuckDist * stuckDist) {
                gunner.startRecovery(profile.getDriveRecoveryTick());
                gunner.setRecoveryCooldownTicks(profile.getDriveRecoveryTick() * 2 + profile.getDriveStuckCheckTick());
            }
            gunner.setLastDriveCheck(vehicle.getX(), vehicle.getZ());
        }

        if (gunner.hasRecoveryTicks()) {
            vehicle.controlUnit.backward = true;
            if (yawDelta > 0) {
                vehicle.controlUnit.right = true;
            } else {
                vehicle.controlUnit.left = true;
            }
            return;
        }

        if (gunner.hasTacticalHoldTicks()) {
            return;
        }

        if (gunner.hasTacticalEvadeTicks()) {
            tickGroundTacticalEvade(gunner, vehicle, target, yawDelta);
            return;
        }

        if (yawDelta > 8) {
            vehicle.controlUnit.right = true;
        } else if (yawDelta < -8) {
            vehicle.controlUnit.left = true;
        }

        if (dist > stopDist * 1.2 && Math.abs(yawDelta) < 80) {
            vehicle.controlUnit.forward = true;
        }
    }

    private static void tickGroundTacticalEvade(GunnerEntity gunner, AbstractVehicle vehicle, Entity target, float targetYawDelta) {
        Vec3 away = vehicle.position().subtract(target.position());
        if (away.horizontalDistanceSqr() < 1.0E-4) {
            away = vehicle.getLookAngle();
        }
        Vec2 awayRot = VectorUtil.vecToRot(new Vec3(away.x, 0, away.z));
        float desiredYaw = awayRot.y + gunner.getTacticalEvadeYawBias();
        float evadeYawDelta = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());

        if (evadeYawDelta > 8.0F) {
            vehicle.controlUnit.right = true;
        } else if (evadeYawDelta < -8.0F) {
            vehicle.controlUnit.left = true;
        }

        if (Math.abs(evadeYawDelta) < 100.0F) {
            vehicle.controlUnit.forward = true;
        } else {
            vehicle.controlUnit.backward = true;
        }

        if (Math.abs(targetYawDelta) > 60.0F) {
            if (targetYawDelta > 0.0F) {
                vehicle.controlUnit.right = true;
            } else {
                vehicle.controlUnit.left = true;
            }
        }
    }

    /**
     * 红外威胁烟雾规避：地面载具（司机 AI）遭遇以下任一威胁时，抛洒 RVP 烟雾弹
     * 并进入"开进烟雾停车"状态（时长略大于烟雾存活）：
     * 1) 被红外族（IR/AIR）导弹锁定跟踪；2) 100 格内出现敌对阵营导弹；3) 被敌对玩家激光照射。
     */
    private static void tickSmokeEvasion(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (!(vehicle instanceof TrackedVehicle) && !(vehicle instanceof WheeledVehicle)) {
            return;
        }
        // 已在停车中，由 tickGroundDriving 的 smoke-hold 分支处理
        if (gunner.hasSmokeHoldTicks()) {
            return;
        }
        // 无烟雾系统的载具直接跳过全部威胁扫描（零成本，避免白跑两轮全量实体遍历）
        boolean hasSmoke = RVP_CountermeasureRuntimeManager.hasSystem(vehicle, RVP_EnumCountermeasureType.SMOKE);
        if (!hasSmoke) {
            return;
        }
        // 错相节流：按实体 id 相位错开各载具的扫描时刻，避免大量载具同刻全量扫实体造成 TPS 尖峰
        if ((gunner.tickCount + vehicle.getId()) % 10 != 0) {
            return;
        }
        if (!(vehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        // 单次遍历同时检测：红外锁定威胁 + 近距敌方来袭导弹
        MissileScanResult scan = scanMissileThreats(gunner, serverLevel, vehicle);
        RVP_MissileEntity threat = scan.irThreat();
        String reason;
        if (threat != null) {
            reason = "红外导弹" + threat.getId() + "锁定";
        } else if (scan.nearbyEnemyMissile()) {
            reason = "近距敌方导弹";
        } else if (findLasingEnemy(serverLevel, vehicle) != null) {
            reason = "被激光照射";
        } else {
            return;
        }
        if ((gunner.tickCount + vehicle.getId()) % 100 == 0) {
            LOGGER.info("[RVP-Gunner-DEBUG] 载具={} tick={} smoke扫描: threat={}({}) hasSmoke={}",
                    vehicle.getVehicleId(), gunner.tickCount,
                    threat == null ? "null" : threat.getId(),
                    threat == null ? "null" : threat.getActiveGuidanceType(), hasSmoke);
        }
        RVP_CountermeasureRuntimeManager.fire(vehicle, RVP_EnumCountermeasureType.SMOKE);
        gunner.setSmokeHoldTicks(SMOKE_HOLD_TICKS);
        LOGGER.info("[RVP-Gunner] 载具={} 因{}，抛烟雾并停车", vehicle.getVehicleId(), reason);
    }

    /** 单次遍历的导弹威胁扫描结果。 */
    private record MissileScanResult(
            /** 正在跟踪本载具的红外族（IR/AIR）导弹（无则 null）。 */
            @Nullable RVP_MissileEntity irThreat,
            /** 是否存在近距敌方来袭导弹。 */
            boolean nearbyEnemyMissile) {}

    /** 单次遍历已加载实体，同时检测红外锁定威胁与近距敌方来袭导弹（两项任一命中即尽早退出）。
     * O(实体) 遍历替代大箱体 getEntities（服务端 gunner 掉 TPS）；敌我判定复用 CIWS 的
     * {@link GunnerTargeting#isFriendlyAmmoOwner}——按弹药 owner（发射者玩家/gunner）判友方。 */
    private static MissileScanResult scanMissileThreats(
            GunnerEntity gunner, net.minecraft.server.level.ServerLevel serverLevel, AbstractVehicle vehicle) {
        AABB irBox = vehicle.getBoundingBox().inflate(INFRARED_THREAT_RADIUS);
        AABB nearbyBox = vehicle.getBoundingBox().inflate(NEARBY_MISSILE_RADIUS);
        net.minecraft.world.scores.Team vehicleTeam = vehicle.getTeam();
        net.minecraft.world.scores.Team gunnerTeam = gunner.getTeam();
        RVP_MissileEntity irThreat = null;
        boolean nearbyEnemyMissile = false;
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (!(entity instanceof RVP_MissileEntity missile) || !missile.isAlive()) {
                continue;
            }
            AABB missileBox = missile.getBoundingBox();
            boolean inIrRange = missileBox.intersects(irBox);
            boolean inNearbyRange = missileBox.intersects(nearbyBox);
            if (!inIrRange && !inNearbyRange) {
                continue;
            }
            // 红外锁定威胁：正在跟踪本载具的 IR/AIR 导弹
            if (irThreat == null && inIrRange && missile.getTargetEntity() == vehicle) {
                RVP_EnumGuidanceType type = missile.getActiveGuidanceType();
                if (type == RVP_EnumGuidanceType.IR || type == RVP_EnumGuidanceType.AIR) {
                    irThreat = missile;
                }
            }
            // 近距敌方来袭导弹：排除本车发射与友方 owner，再做来袭角判定
            if (!nearbyEnemyMissile && inNearbyRange && missile.getShooterVehicle() != vehicle
                    && !GunnerTargeting.isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam,
                            missile.getOwner())) {
                Vec3 toVehicle = vehicle.getBoundingBox().getCenter().subtract(missile.position());
                Vec3 velocity = missile.getDeltaMovement();
                // 速度过低（刚冷发射/加速段）暂不判来袭，等下一轮扫描再确认；
                // 速度方向与"导弹→本车"夹角超过阈值（掠过/飞离的弹）不算威胁
                if (velocity.lengthSqr() >= INBOUND_MIN_SPEED * INBOUND_MIN_SPEED
                        && velocity.normalize().dot(toVehicle.normalize())
                                >= Math.cos(Math.toRadians(INBOUND_MISSILE_ANGLE_DEG))) {
                    nearbyEnemyMissile = true;
                }
            }
            // 两项都命中即可提前结束遍历
            if (irThreat != null && nearbyEnemyMissile) {
                break;
            }
        }
        return new MissileScanResult(irThreat, nearbyEnemyMissile);
    }

    /** 找正在照射本载具的敌对玩家：其活跃照射点落入本车包围盒 LASER_SPOT_RADIUS 膨胀范围。 */
    @Nullable
    private static net.minecraft.server.level.ServerPlayer findLasingEnemy(
            net.minecraft.server.level.ServerLevel serverLevel, AbstractVehicle vehicle) {
        net.minecraft.world.scores.Team vehicleTeam = vehicle.getTeam();
        AABB spotBox = vehicle.getBoundingBox().inflate(LASER_SPOT_RADIUS);
        for (Map.Entry<java.util.UUID, Vec3> entry
                : org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession.activeDesignations(LASER_FRESH_MS).entrySet()) {
            net.minecraft.server.level.ServerPlayer player =
                    serverLevel.getServer().getPlayerList().getPlayer(entry.getKey());
            // 玩家已离线则跳过（陈旧会话不触发）
            if (player == null || !player.isAlive()) {
                continue;
            }
            // 友方玩家照射不算威胁
            if (vehicleTeam != null && player.getTeam() != null && player.getTeam().isAlliedTo(vehicleTeam)) {
                continue;
            }
            Vec3 spot = entry.getValue();
            if (spotBox.contains(spot.x, spot.y, spot.z)) {
                return player;
            }
        }
        return null;
    }

    /** 烟雾躲避驾驶：向最近的烟雾云开进，进入云内即停车（无控制输入）；烟雾消散则提前结束。 */
    private static void tickSmokeHoldDrive(GunnerEntity gunner, AbstractVehicle vehicle) {
        RVP_SmokeEntity smoke = findNearbySmoke(vehicle, SMOKE_LOOK_RADIUS);
        if (smoke == null) {
            // 烟雾已散，提前结束停车
            gunner.setSmokeHoldTicks(0);
            return;
        }
        Vec3 toSmoke = smoke.position().subtract(vehicle.position());
        double dist = Math.sqrt(toSmoke.x * toSmoke.x + toSmoke.z * toSmoke.z);
        double radius = Math.max(1.0, smoke.getCurrentRadius());
        if (dist > radius * 0.6) {
            // 未进云：转向烟雾并前进
            Vec2 rot = VectorUtil.vecToRot(new Vec3(toSmoke.x, 0, toSmoke.z));
            float yawDelta = Mth.wrapDegrees(rot.y - vehicle.getYRot());
            if (yawDelta > 8.0F) {
                vehicle.controlUnit.right = true;
            } else if (yawDelta < -8.0F) {
                vehicle.controlUnit.left = true;
            }
            if (Math.abs(yawDelta) < 80.0F) {
                vehicle.controlUnit.forward = true;
            }
        }
        // 已进云（dist <= radius*0.6）：无控制输入 = 停车
    }

    /** 查找最近的存活烟雾云实体。 */
    @Nullable
    private static RVP_SmokeEntity findNearbySmoke(AbstractVehicle vehicle, double radius) {
        AABB box = vehicle.getBoundingBox().inflate(radius);
        RVP_SmokeEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Entity entity : vehicle.level().getEntities(vehicle, box,
                e -> e instanceof RVP_SmokeEntity && e.isAlive())) {
            double d = entity.distanceToSqr(vehicle);
            if (d < bestSqr) {
                bestSqr = d;
                best = (RVP_SmokeEntity) entity;
            }
        }
        return best;
    }

    private static boolean tickFixedWingDriving(GunnerEntity gunner, FixedWingVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        if (target == null) {
            tickFixedWingCruise(gunner, vehicle, profile, false);
            return false;
        }
        boolean allowFire = ensureAirPhase(gunner, vehicle, profile);
        boolean attackPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK;
        tickFixedWingCruise(gunner, vehicle, profile, attackPhase);

        Vec3 delta = target.position().subtract(vehicle.position());
        double horizontalDist = new Vec3(delta.x, 0, delta.z).length();
        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        boolean breakAway = horizontalDist < Math.max(stopDist * 4.0, 48.0);

        Vec3 aimPoint;
        if (!attackPhase || breakAway) {
            Vec3 forward = vehicle.getLookAngle().normalize();
            aimPoint = vehicle.position().add(forward.scale(128)).add(0, 30, 0);
        } else {
            aimPoint = target.position().add(target.getDeltaMovement().scale(10)).add(0, 12, 0);
        }

        Vec3 homePos = gunner.getHomePos();
        if (homePos != null) {
            double d = vehicle.position().distanceTo(homePos);
            double min = profile.getFixedwingCombatRadiusMin();
            double max = profile.getFixedwingCombatRadiusMax();
            if (max > 0.0 && max >= min) {
                if (d > max) {
                    // 超出作战半径：回航优先。回航锚点延迟到 ~1.3×半径（过早掉头会让 gunner
                    // 追不到索敌范围（×6）内交战的敌机 → "不爱打"）；~2.3×半径完全回家，
                    // 兼顾"战斗半径约束"与"愿意追击到 1000 格左右"。
                    Vec3 homeDir = homePos.subtract(vehicle.position());
                    if (homeDir.lengthSqr() < 1.0E-4) {
                        homeDir = vehicle.getLookAngle();
                    }
                    homeDir = homeDir.normalize();
                    double returnWeight = Math.min(1.0,
                            Math.max(0.0, (d - max * 1.3) / Math.max(max, 1.0)));
                    Vec3 chaseDir = aimPoint.subtract(vehicle.position());
                    if (chaseDir.lengthSqr() < 1.0E-4) {
                        chaseDir = vehicle.getLookAngle();
                    }
                    aimPoint = vehicle.position()
                            .add(chaseDir.normalize().scale(200.0 * (1.0 - returnWeight)))
                            .add(homeDir.scale(400.0 * returnWeight))
                            .add(0, 20, 0);
                } else if (d < min) {
                    // 出生点过近：向外偏离开，避免死守家里不动
                    Vec3 out = vehicle.position().subtract(homePos);
                    if (out.lengthSqr() < 1.0E-4) {
                        out = vehicle.getLookAngle();
                    }
                    aimPoint = aimPoint.add(out.normalize().scale(200.0));
                }
            }
        }

        Vec2 desiredRot = VectorUtil.vecToRot(aimPoint.subtract(vehicle.position()));
        vehicle.controlUnit.yRot = desiredRot.y;
        vehicle.controlUnit.yRotKeep = false;

        vehicle.controlUnit.forward = true;

        if (vehicle.onGround()) {
            float groundYawDelta = Mth.wrapDegrees(desiredRot.y - vehicle.getYRot());
            if (groundYawDelta > 6) {
                vehicle.controlUnit.rightYaw = true;
            } else if (groundYawDelta < -6) {
                vehicle.controlUnit.leftYaw = true;
            }
        }
        return allowFire;
    }

    private static boolean tickRotaryDriving(GunnerEntity gunner, RotaryWingVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        if (target == null) {
            tickRotaryCruise(gunner, vehicle, profile, false);
            return false;
        }
        boolean allowFire = ensureRotaryAirPhase(gunner, profile);
        boolean attackPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK;
        tickRotaryCruise(gunner, vehicle, profile, attackPhase);

        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double takeoffAgl = Math.min(profile.getRotaryCruiseAltitudeMin(), 25.0);
        if (currentAgl < takeoffAgl) {
            vehicle.hoverMode = true;
            vehicle.controlUnit.up = true;
            vehicle.controlUnit.yRotKeep = true;
            vehicle.controlUnit.xRotKeep = true;
            return false;
        }

        vehicle.hoverMode = false;
        Vec3 delta = target.position().subtract(vehicle.position());
        double horizontalDist = new Vec3(delta.x, 0, delta.z).length();
        Vec3 facingVec = attackPhase ? delta : vehicle.position().subtract(target.position());
        if (facingVec.horizontalDistanceSqr() < 1.0E-4) {
            facingVec = vehicle.getLookAngle();
        }
        Vec2 facingRot = VectorUtil.vecToRot(facingVec);
        Vec2 targetRot = VectorUtil.vecToRot(delta);
        vehicle.controlUnit.yRot = facingRot.y;
        vehicle.controlUnit.yRotKeep = false;
        vehicle.controlUnit.xRotKeep = false;

        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        float desiredPitch = computeRotaryPitch(vehicle, profile, attackPhase, currentAgl, horizontalDist, stopDist, targetRot);
        vehicle.controlUnit.xRot = desiredPitch;
        return allowFire;
    }

    private static double pickCruiseAgl(double currentAgl, double minAgl, double maxAgl) {
        double min = Math.max(0.0, minAgl);
        double max = Math.max(min, maxAgl);
        double mid = (min + max) * 0.5;
        double deadBand = Math.max(5.0, (max - min) * 0.08);
        if (currentAgl < min) {
            return min;
        }
        if (currentAgl > max) {
            return max;
        }
        if (Math.abs(currentAgl - mid) <= deadBand) {
            return currentAgl;
        }
        return mid;
    }

    private static void tickFixedWingCruise(GunnerEntity gunner, FixedWingVehicle vehicle, GunnerProfile profile, boolean attackPhase) {
        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double min = profile.getFixedwingCruiseAltitudeMin();
        double max = profile.getFixedwingCruiseAltitudeMax();
        double desiredAgl;
        if (attackPhase) {
            desiredAgl = Mth.clamp(175.0, min, max);
        } else {
            double span = Math.max(max - min, 0.0);
            double lowCruise = min + span * 0.2;
            double highCruise = min + span * 0.8;
            // Give each gunner a slow, per-entity altitude wave so fixed-wing AI does not hug max altitude forever.
            double wave = (Math.sin((gunner.tickCount + gunner.getId() * 37.0) * 0.0125) + 1.0) * 0.5;
            desiredAgl = Mth.lerp(wave, lowCruise, highCruise);
        }
        if (currentAgl < min) {
            desiredAgl = min;
        } else if (currentAgl > max) {
            desiredAgl = max;
        }
        double desiredAlt = groundY + desiredAgl;
        double altErr = desiredAlt - vehicle.getY();
        float pitchCmd = (float) Mth.clamp(-altErr * 0.25, -18.0, 10.0);
        if (!attackPhase) {
            pitchCmd = (float) Mth.clamp(pitchCmd - 4.0F, -18.0, 10.0);
        }
        if (vehicle.onGround() && vehicle.getY() < 68) {
            pitchCmd = -10.0F;
        }
        vehicle.controlUnit.forward = true;
        vehicle.controlUnit.xRot = pitchCmd;
        vehicle.controlUnit.xRotKeep = false;
    }

    private static void tickRotaryCruise(GunnerEntity gunner, RotaryWingVehicle vehicle, GunnerProfile profile, boolean attackPhase) {
        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double min = profile.getRotaryCruiseAltitudeMin();
        double max = profile.getRotaryCruiseAltitudeMax();
        double desiredAgl = attackPhase ? (min + max) * 0.5 : max;
        if (currentAgl < min) {
            desiredAgl = min;
        } else if (currentAgl > max) {
            desiredAgl = max;
        }
        double desiredAlt = groundY + desiredAgl;
        double altitudeError = desiredAlt - vehicle.getY();
        if (vehicle.getCollectivePitch() < 55.0f) {
            vehicle.controlUnit.up = true;
        } else if (altitudeError > 2.0) {
            vehicle.controlUnit.up = true;
        } else if (altitudeError < -2.0) {
            vehicle.controlUnit.down = true;
        }
    }

    private static void tickGroundWander(GunnerEntity gunner, AbstractVehicle vehicle, GunnerProfile profile) {
        if (!profile.isGroundWanderEnabled()) {
            return;
        }
        int cooldown = gunner.getGroundBigTurnCooldown();
        int turning = gunner.getGroundBigTurnTicks();
        if (turning > 0) {
            gunner.setGroundBigTurnTicks(turning - 1);
            float yawDelta = Mth.wrapDegrees(gunner.getGroundBigTurnTargetYaw() - vehicle.getYRot());
            if (Math.abs(yawDelta) > 6) {
                if (yawDelta > 0) {
                    vehicle.controlUnit.right = true;
                } else {
                    vehicle.controlUnit.left = true;
                }
            } else {
                gunner.setGroundBigTurnTicks(0);
            }
            return;
        }
        if (cooldown > 0) {
            gunner.setGroundBigTurnCooldown(cooldown - 1);
        } else {
            int minTick = profile.getGroundBigTurnIntervalTickMin();
            int maxTick = profile.getGroundBigTurnIntervalTickMax();
            int next = minTick + gunner.getRandom().nextInt(Math.max(1, maxTick - minTick + 1));
            gunner.setGroundBigTurnCooldown(next);

            float minDeg = profile.getGroundBigTurnAngleDegMin();
            float maxDeg = profile.getGroundBigTurnAngleDegMax();
            float ang = minDeg + gunner.getRandom().nextFloat() * Math.max(0.0F, maxDeg - minDeg);
            if (gunner.getRandom().nextBoolean()) {
                ang = -ang;
            }
            gunner.setGroundBigTurnTargetYaw(vehicle.getYRot() + ang);
            gunner.setGroundBigTurnTicks(profile.getGroundBigTurnDurationTick());
            return;
        }

        vehicle.controlUnit.forward = true;
        if ((gunner.tickCount / 40) % 2 == 0) {
            vehicle.controlUnit.left = true;
        }
    }

    private static boolean ensureAirPhase(GunnerEntity gunner, FixedWingVehicle vehicle, GunnerProfile profile) {
        if (!gunner.isAirPhaseInitialized()) {
            gunner.setAirPhaseInitialized(true);
            gunner.setAirPhase(AIR_PHASE_DISENGAGE);
            int initial = pickScaledTickRange(gunner,
                    profile.getAirInitialDisengageTickMin(),
                    profile.getAirInitialDisengageTickMax(),
                    FIXEDWING_INITIAL_DISENGAGE_SCALE,
                    40,
                    180);
            gunner.setAirPhaseTicks(initial);
            return false;
        }
        int ticks = gunner.getAirPhaseTicks();
        if (ticks <= 0) {
            int nextPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK ? AIR_PHASE_DISENGAGE : AIR_PHASE_ATTACK;
            if (nextPhase == AIR_PHASE_ATTACK) {
                double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
                double currentAgl = vehicle.getY() - groundY;
                if (currentAgl < FIXEDWING_ATTACK_ENTRY_MIN_AGL) {
                    gunner.setAirPhase(AIR_PHASE_DISENGAGE);
                    gunner.setAirPhaseTicks(20);
                    return false;
                }
            }
            gunner.setAirPhase(nextPhase);
            int nextTicks = nextPhase == AIR_PHASE_ATTACK
                    ? scaleAirTicks(profile.getAirAttackPhaseTick(), FIXEDWING_ATTACK_SCALE, 140, 420)
                    : scaleAirTicks(profile.getAirDisengagePhaseTick(), FIXEDWING_DISENGAGE_SCALE, 40, 140);
            gunner.setAirPhaseTicks(nextTicks);
        } else {
            gunner.setAirPhaseTicks(ticks - 1);
        }
        return gunner.getAirPhase() == AIR_PHASE_ATTACK;
    }

    private static boolean ensureRotaryAirPhase(GunnerEntity gunner, GunnerProfile profile) {
        if (!gunner.isAirPhaseInitialized()) {
            gunner.setAirPhaseInitialized(true);
            gunner.setAirPhase(AIR_PHASE_DISENGAGE);
            int initial = pickScaledTickRange(gunner,
                    profile.getAirInitialDisengageTickMin(),
                    profile.getAirInitialDisengageTickMax(),
                    ROTARY_INITIAL_DISENGAGE_SCALE,
                    20,
                    90);
            gunner.setAirPhaseTicks(initial);
            return false;
        }

        int ticks = gunner.getAirPhaseTicks();
        if (ticks <= 0) {
            int nextPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK ? AIR_PHASE_DISENGAGE : AIR_PHASE_ATTACK;
            gunner.setAirPhase(nextPhase);
            int nextTicks = nextPhase == AIR_PHASE_ATTACK
                    ? scaleAirTicks(profile.getAirAttackPhaseTick(), ROTARY_ATTACK_SCALE, 120, 320)
                    : scaleAirTicks(profile.getAirDisengagePhaseTick(), ROTARY_DISENGAGE_SCALE, 40, 120);
            gunner.setAirPhaseTicks(nextTicks);
        } else {
            gunner.setAirPhaseTicks(ticks - 1);
        }
        return gunner.getAirPhase() == AIR_PHASE_ATTACK;
    }

    private static int pickScaledTickRange(GunnerEntity gunner, int min, int max, double scale, int floor, int ceil) {
        int scaledMin = scaleAirTicks(min, scale, floor, ceil);
        int scaledMax = scaleAirTicks(max, scale, floor, ceil);
        return scaledMin + gunner.getRandom().nextInt(Math.max(1, scaledMax - scaledMin + 1));
    }

    private static int scaleAirTicks(int value, double scale, int floor, int ceil) {
        int scaled = (int) Math.round(value * scale);
        return Mth.clamp(scaled, floor, ceil);
    }

    private static float computeRotaryPitch(RotaryWingVehicle vehicle, GunnerProfile profile, boolean attackPhase,
                                            double currentAgl, double horizontalDist, double stopDist, Vec2 targetRot) {
        float desiredPitch = Mth.clamp(targetRot.x * 0.75F, -8.0F, 10.0F);
        double farDist = Math.max(stopDist * 2.0, 28.0);
        double nearDist = Math.max(stopDist * 0.9, 12.0);

        if (attackPhase) {
            if (horizontalDist > farDist) {
                desiredPitch = Mth.clamp(desiredPitch + 2.5F, -8.0F, 11.0F);
            } else if (horizontalDist < nearDist) {
                desiredPitch = Mth.clamp(desiredPitch - 4.0F, -10.0F, 8.0F);
            }
        } else {
            desiredPitch = Mth.clamp(desiredPitch - 1.5F, -8.0F, 7.0F);
        }

        double descentRate = vehicle.getDeltaMovement().y;
        double lowAgl = Math.max(14.0, profile.getRotaryCruiseAltitudeMin() * 0.45);
        double hardLowAgl = Math.max(8.0, profile.getRotaryCruiseAltitudeMin() * 0.3);
        if (currentAgl < lowAgl || descentRate < -0.18) {
            desiredPitch = Math.min(desiredPitch, -4.0F);
            vehicle.controlUnit.up = true;
        }
        if (currentAgl < hardLowAgl || descentRate < -0.35) {
            desiredPitch = Math.min(desiredPitch, -8.0F);
            vehicle.controlUnit.up = true;
        }
        return desiredPitch;
    }

    private static void tickCountermeasure(GunnerEntity gunner, AbstractVehicle vehicle, GunnerProfile profile) {
        if (gunner.getCountermeasureCooldown() > 0) {
            return;
        }
        AmmoEntity threat = GunnerTargeting.findAmmoThreat(gunner, vehicle, profile.getCountermeasureRange());
        if (threat == null) {
            return;
        }

        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
                AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
                if (isCountermeasureWeapon(weapon) && weapon.hasAmmo() && !weapon.isCoolingDown() && !weapon.isReloading()) {
                    weaponUnit.aim(threat.position());
                    weaponUnit.shoot(index, weaponUnit.aimContexts(), gunner);
                    gunner.setCountermeasureCooldown(profile.getCountermeasureCooldownTick());
                    return;
                }
            }
        }
    }

    /**
     * 主动ECM 自动触发（Gunner AI）：被雷达锁定或导弹来袭时尝试释放。
     *
     * <p>触发条件：RWR 存在 RADAR_LOCK/MISSILE_LAUNCH 告警，或 {@code findAmmoThreat} 在 400 格内发现威胁；
     * 可用性：载具存在存活的 ECM_ACTIVE 骨块；冷却由服务端 {@code RVP_EcmActiveManager} 判定。</p>
     */
    /** 主动ECM 调试日志节流（按载具 id）。 */
    private static final Map<Integer, Long> GUNNER_ECM_DBG = new HashMap<>();
    /** 主动ECM gunner 调试开关。 */
    private static final boolean GUNNER_ECM_DBG_ON = true;

    /** 记录 gunner 主动ECM 决策（控制台 + logs/rvp_ecm_server.log，单客户端同目录）。 */
    private static void rvpEcmDbg(AbstractVehicle vehicle, String msg) {
        if (!GUNNER_ECM_DBG_ON) {
            return;
        }
        long now = vehicle.level().getGameTime();
        Long last = GUNNER_ECM_DBG.get(vehicle.getId());
        if (last != null && now - last < 10) {
            return;
        }
        GUNNER_ECM_DBG.put(vehicle.getId(), now);
        String line = "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                + "][GunnerECM] veh=" + vehicle.getId() + " " + msg;
        System.out.println(line);
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            Path f = dir.resolve("rvp_ecm_server.log");
            if (Files.exists(f) && Files.size(f) > 256 * 1024L) {
                Files.delete(f);
            }
            Files.write(f, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 调试日志写失败不影响游戏
        }
    }

    private static void tickEcmActive(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return;
        }
        // 是否装备主动ECM
        var devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            rvpEcmDbg(vehicle, "无主动ECM设备(devices为空)");
            return;
        }
        boolean hasAlive = false;
        for (String bone : devices.keySet()) {
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), bone, BoneModuleType.ECM_ACTIVE)) {
                hasAlive = true;
                break;
            }
        }
        if (!hasAlive) {
            rvpEcmDbg(vehicle, "ECM模块未激活(已损毁或未通电)");
            return;
        }
        // 触发条件：被锁定或导弹来袭
        boolean shouldFire = false;
        // RWR 锁定检查
        if (vehicle.warningReceiver != null) {
            for (var entry : vehicle.warningReceiver.targets.entrySet()) {
                WarnType wt = entry.getValue().warnType();
                if (wt == WarnType.RADAR_LOCK || wt == WarnType.MISSILE_LAUNCH) {
                    shouldFire = true;
                    break;
                }
            }
        }
        // 导弹威胁检查：以主动ECM 的有效干扰半径作为触发距离（不再受箔条 32m 硬上限限制）。
        // 主动ECM 属纯自卫：只要是有威胁的来袭危险弹药即释放，不区分敌我（仅排除本机自身发射的导弹），
        // 因此即便是玩家自己的 gunner 在单人测试中被自己导弹攻击也会触发——比箔条的"仅敌对"更合理。
        if (!shouldFire) {
            // 取所有 ECM 设备中的最大干扰半径作为触发阈值（弹药/载具干扰半径与 200m 兜底）
            double triggerRadius = 200.0;
            for (BoneEcmActiveConfig cfg : devices.values()) {
                triggerRadius = Math.max(triggerRadius, cfg.ammoJamRadius());
                triggerRadius = Math.max(triggerRadius, cfg.vehicleJamRadius());
            }
            // 调试：统计触发半径内危险弹药数量，定位"为何不触发"
            int near = 0, hostileDanger = 0;
            AmmoEntity sample = null;
            AmmoEntity threat = null;
            for (Entity e : GunnerTargeting.collectTargetEntities(vehicle, triggerRadius, ent -> ent instanceof AmmoEntity ammo
                    && ammo.isAlive() && ammo.vehicle != vehicle)) {
                AmmoEntity ammo = (AmmoEntity) e;
                double d = ammo.position().distanceToSqr(vehicle.position());
                if (d > triggerRadius * triggerRadius) {
                    continue;
                }
                near++;
                boolean friendly = GunnerTargeting.isFriendlyAmmoOwner(gunner, vehicle, vehicle.getTeam(), gunner.getTeam(), ammo.getOwner());
                boolean danger = GunnerTargeting.isDangerousAmmo(ammo);
                if (sample == null) {
                    sample = ammo;
                }
                if (danger && !friendly) {
                    hostileDanger++;
                }
                // ECM 触发判定：危险弹药且朝本机飞来（闭合速度为正），不要求敌我之分
                if (threat == null && danger) {
                    Vec3 vel = ammo.getDeltaMovement();
                    if (vel.lengthSqr() >= 0.04) {
                        Vec3 toV = vehicle.position().subtract(ammo.position());
                        if (vel.dot(toV.normalize()) > 0.0) {
                            threat = ammo;
                        }
                    }
                }
            }
            rvpEcmDbg(vehicle, "扫描: tr=" + (int) triggerRadius + " 半径内弹药=" + near
                    + " 敌对危险=" + hostileDanger
                    + (sample != null ? " 样本#" + sample.getId()
                        + " friendly=" + GunnerTargeting.isFriendlyAmmoOwner(gunner, vehicle, vehicle.getTeam(), gunner.getTeam(), sample.getOwner())
                        + " danger=" + GunnerTargeting.isDangerousAmmo(sample) : "")
                    + " threat=" + (threat != null ? "有#" + threat.getId() : "null"));
            if (threat != null) {
                shouldFire = true;
            }
        }
        if (!shouldFire) {
            return;
        }
        rvpEcmDbg(vehicle, "触发释放主动ECM");
        // 尝试释放（服务端校验冷却）
        if (vehicle.level() instanceof ServerLevel) {
            RVP_EcmActiveManager.tryFireForVehicle(vehicle);
        }
    }

    private static void refillDriverVehicle(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (!gunner.markDriverRide(vehicle.getId())) {
            return;
        }

        if (!gunner.hasHomePos()) {
            gunner.setHomePos(vehicle.position());
        }
        vehicle.toggleEngine(true);
        vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                    if (weapon.getData().getWeaponId() == null) {
                        continue;
                    }
                    weapon.setRemainAmmo(weapon.getMaxCapacity());
                    forceSetReloadTime(weapon, 0);
                }
            }
        }
    }

    private static void sustainDriverInfiniteAmmo(AbstractVehicle vehicle) {
        long now = System.currentTimeMillis();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                if (weapon.getData().getWeaponId() == null) {
                    DRIVER_AMMO_READY_TIME.remove(weapon);
                    continue;
                }
                if (weapon.getRemainAmmo() > 0) {
                    DRIVER_AMMO_READY_TIME.remove(weapon);
                    continue;
                }

                Long readyTime = DRIVER_AMMO_READY_TIME.get(weapon);
                if (readyTime == null) {
                    readyTime = now + getDriverInfiniteAmmoReloadMs(weapon);
                    DRIVER_AMMO_READY_TIME.put(weapon, readyTime);
                }

                long remainMs = Math.max(0L, readyTime - now);
                if (remainMs > 0L) {
                    forceSetReloadTime(weapon, msToTicks(remainMs));
                    continue;
                }

                weapon.setRemainAmmo(Math.max(1, weapon.getMaxCapacity()));
                forceSetReloadTime(weapon, 0);
                DRIVER_AMMO_READY_TIME.remove(weapon);
            }
        }
    }

    private static void clearDriverInfiniteAmmoTimers(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                if (weapon.getData().getWeaponId() == null) {
                    DRIVER_AMMO_READY_TIME.remove(weapon);
                    continue;
                }
                DRIVER_AMMO_READY_TIME.remove(weapon);
                forceSetReloadTime(weapon, 0);
            }
        }
    }

    /**
     * [RVP] accessor 已移除：RVP 武器走公共方法直调（{@code RVP_WeaponBase#ywzj_rvp$setReloadTime}），
     * 本体武器用 ObfuscationReflectionHelper 反射调用（正确处理开发/发布映射），失败时仅功能降级，不崩溃。
     */
    @Nullable
    private static Method SET_RELOAD_TIME_METHOD;

    private static void forceSetReloadTime(AbstractVehicleWeapon<?> weapon, int ticks) {
        if (weapon == null) {
            return;
        }
        if (weapon instanceof RVP_WeaponBase rvpWeapon) {
            rvpWeapon.ywzj_rvp$setReloadTime(ticks);
            return;
        }
        if (SET_RELOAD_TIME_METHOD == null) {
            try {
                SET_RELOAD_TIME_METHOD = ObfuscationReflectionHelper.findMethod(
                        AbstractVehicleWeapon.class, "setReloadTime", int.class);
                SET_RELOAD_TIME_METHOD.setAccessible(true);
            } catch (Throwable t) {
                LOGGER.warn("[GunnerBrain] 无法解析 setReloadTime 反射方法，无限弹药 reload 强制归零将失效", t);
                return;
            }
        }
        try {
            SET_RELOAD_TIME_METHOD.invoke(weapon, ticks);
        } catch (Throwable t) {
            LOGGER.debug("[GunnerBrain] setReloadTime 反射调用失败", t);
        }
    }

    private static long getDriverInfiniteAmmoReloadMs(AbstractVehicleWeapon<?> weapon) {
        long reloadMs = Math.max(0, weapon.getData().getReload().getTime()) * 50L;
        long cooldownMs = Math.max(0L, weapon.getShootInterval());
        if (weapon.getMaxCapacity() <= 1) {
            return reloadMs + cooldownMs;
        }
        return reloadMs;
    }

    private static int msToTicks(long ms) {
        return Math.max(1, (int) ((ms + 49L) / 50L));
    }

    /**
     * SEAD 复仇（反辐射）处理：仅固定翼/旋翼 + 司机 AI 时由 gunner 自行驾驶完成
     * "被雷达锁定 → 抛干扰物 → 飞离 → 回旋 → 锁&打 → 退出"。返回 true 表示本 tick
     * 已由 SEAD 接管（含驾驶与复仇开火），调用方跳过常规 driving/combat。
     *
     * <p>入口判定：被锁定的瞬间若锁定我的雷达已在反辐射弹射击门控内，先立即发射 1 枚，
     * 再进入复仇阶段；复仇阶段门控通过再补 1 枚（共至多 2 枚）。</p>
     *
     * <p>TPS 控制：触发检测按 {@link #SEAD_THREAT_SCAN_INTERVAL} 节流，不逐 tick 全量遍历；
     * 复仇期间复用已存储的复仇目标 id，不重复扫描。</p>
     */
    private static boolean tickSead(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit, GunnerProfile profile) {
        if (!(vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle)) {
            return false;
        }
        int mode = gunner.getSeadMode();
        if (mode == SEAD_NONE) {
            // 触发检测（节流）：仅"被敌方雷达锁定"且"带反辐射弹"且"不在复仇冷却内"时进入 SEAD 复仇
            if (gunner.getSeadCooldownTicks() > 0
                    || gunner.tickCount % SEAD_THREAT_SCAN_INTERVAL != 0 || weaponUnit == null) {
                return false;
            }
            Entity radarSource = findRadarLockingEntity(gunner, vehicle);
            if (radarSource == null || findArmWeaponIndex(weaponUnit) < 0) {
                return false;
            }
            // 入口判定：若锁定我的雷达已在反辐射弹射击门控内，立即先射 1 枚（不等），再进复仇
            int armIdx = findArmWeaponIndex(weaponUnit);
            AbstractVehicleWeapon<?> arm = weaponUnit.getIndexedWeapons().get(armIdx);
            if (gunner.getMissileCooldown() <= 0
                    && GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, arm, radarSource)) {
                fireArmMissileAt(gunner, weaponUnit, radarSource);
                gunner.setSeadImmediateFired(true);
            }
            // 抛干扰物（箔条对抗雷达锁定）并进入飞离阶段
            RVP_CountermeasureRuntimeManager.fire(vehicle, RVP_EnumCountermeasureType.CHAFF);
            gunner.setSeadMode(SEAD_FLY_AWAY);
            gunner.setSeadTicks(SEAD_FLY_AWAY_TICK);
            gunner.setSeadTotalTicks(0);
            gunner.setSeadRevengeTargetId(radarSource.getId());
            gunner.setSeadRevengeFired(false);
            return true;
        }
        // 复仇推进：累计总时长，超保险上限强制退出
        gunner.setSeadTotalTicks(gunner.getSeadTotalTicks() + 1);
        if (gunner.getSeadTotalTicks() > SEAD_TIMEOUT_TICK) {
            clearSead(gunner);
            return false;
        }
        Entity revengeTarget = vehicle.level().getEntity(gunner.getSeadRevengeTargetId());
        if (revengeTarget == null || !revengeTarget.isAlive()) {
            clearSead(gunner);
            return false;
        }
        int ticks = gunner.getSeadTicks();
        if (ticks > 0) {
            gunner.setSeadTicks(ticks - 1);
        }
        switch (mode) {
            case SEAD_FLY_AWAY:
                // 飞离：背对锁定者拉开距离，跑完时长进入回旋
                tickSeadFly(gunner, vehicle, revengeTarget, false);
                if (ticks <= 0) {
                    gunner.setSeadMode(SEAD_REVERSAL);
                    gunner.setSeadTicks(SEAD_REVERSAL_TICK);
                }
                break;
            case SEAD_REVERSAL:
            case SEAD_LOCK_FIRE:
                // 回旋/锁定发射：转向目标并每 tick 检查攻击门控，门控通过立即发射复仇一发
                tickSeadFly(gunner, vehicle, revengeTarget, true);
                if (tryFireRevenge(gunner, weaponUnit, revengeTarget)) {
                    clearSead(gunner);
                    return true;
                }
                if (mode == SEAD_REVERSAL && ticks <= 0) {
                    gunner.setSeadMode(SEAD_LOCK_FIRE);
                    gunner.setSeadTicks(SEAD_LOCK_FIRE_TICK);
                } else if (mode == SEAD_LOCK_FIRE && ticks <= 0) {
                    clearSead(gunner);
                }
                break;
            default:
                clearSead(gunner);
                break;
        }
        return true;
    }

    /** SEAD 复仇阶段驾驶：towardTarget=true 转向目标，false 背对目标飞离（含高度保持）。 */
    private static void tickSeadFly(GunnerEntity gunner, AbstractVehicle vehicle, Entity target, boolean towardTarget) {
        Vec3 aimPoint;
        if (towardTarget) {
            aimPoint = target.position().add(0, 12, 0);
        } else {
            Vec3 away = vehicle.position().subtract(target.position());
            if (away.lengthSqr() < 1.0E-4) {
                away = vehicle.getLookAngle();
            }
            aimPoint = vehicle.position().add(away.normalize().scale(256)).add(0, 20, 0);
        }
        Vec2 desiredRot = VectorUtil.vecToRot(aimPoint.subtract(vehicle.position()));
        vehicle.controlUnit.yRot = desiredRot.y;
        vehicle.controlUnit.yRotKeep = false;
        if (vehicle instanceof FixedWingVehicle fixedWing) {
            // 固定翼：前飞 + 高度保持（复用巡航的高度控制思路，取适中巡航高度）
            double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
            double desiredAlt = groundY + 180.0;
            double altErr = desiredAlt - vehicle.getY();
            float pitchCmd = (float) Mth.clamp(-altErr * 0.25, -18.0, 10.0);
            fixedWing.controlUnit.forward = true;
            fixedWing.controlUnit.xRot = pitchCmd;
            fixedWing.controlUnit.xRotKeep = false;
        } else if (vehicle instanceof RotaryWingVehicle rotary) {
            // 旋翼：悬停转向 + 高度保持
            rotary.hoverMode = false;
            double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
            double desiredAlt = groundY + 60.0;
            if (vehicle.getY() < desiredAlt - 4.0) {
                rotary.controlUnit.up = true;
            } else if (vehicle.getY() > desiredAlt + 4.0) {
                rotary.controlUnit.down = true;
            }
        }
    }

    /**
     * 寻找锁定本机的敌方雷达载具（服务端扫描）。
     * 复用 RVP_GunnerVehicleTickService 的雷达锁定检测模式：遍历已加载载具的 RadarUnit，
     * 命中"敌方载具雷达 isOn 且 getLockedEntity()==本机"即返回该载具。
     */
    @Nullable
    private static Entity findRadarLockingEntity(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        AABB box = vehicle.getBoundingBox().inflate(SEAD_RADAR_LOCK_RANGE);
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle enemy) || enemy == vehicle || !enemy.isAlive()
                    || !enemy.getBoundingBox().intersects(box)) {
                continue;
            }
            if (!isHostileTo(gunner, vehicle, enemy)) {
                continue;
            }
            for (PartUnit<?> part : enemy.getPartUnits()) {
                if (part instanceof RadarUnit radar && radar.isOn() && radar.getLockedEntity() == vehicle) {
                    return enemy;
                }
            }
        }
        return null;
    }

    /** 敌我判定：gunner 阵营优先（镜像 GunnerTargeting.isRelativeHostileGunnerVehicle），
     *  其次队伍，无队伍时排除本 gunner 的主人。 */
    private static boolean isHostileTo(GunnerEntity gunner, AbstractVehicle vehicle, AbstractVehicle enemy) {
        if (enemy.getDriver() instanceof GunnerEntity targetGunner) {
            RVP_EnumGunnerFaction sourceFaction = gunner.getProfileFaction();
            RVP_EnumGunnerFaction targetFaction = targetGunner.getProfileFaction();
            if (sourceFaction == RVP_EnumGunnerFaction.ENEMY) {
                return targetFaction == RVP_EnumGunnerFaction.FRIENDLY
                        || targetFaction == RVP_EnumGunnerFaction.TEAM;
            }
            if (sourceFaction == RVP_EnumGunnerFaction.FRIENDLY) {
                return targetFaction == RVP_EnumGunnerFaction.ENEMY;
            }
            if (sourceFaction == RVP_EnumGunnerFaction.TEAM) {
                if (targetFaction == RVP_EnumGunnerFaction.ENEMY) {
                    return true;
                }
                if (targetFaction == RVP_EnumGunnerFaction.TEAM) {
                    Team sourceTeam = gunner.getTeam();
                    Team targetTeam = targetGunner.getTeam();
                    return sourceTeam == null || targetTeam == null || !targetTeam.isAlliedTo(sourceTeam);
                }
            }
            return false;
        }
        Team vehicleTeam = vehicle.getTeam();
        Team enemyTeam = enemy.getTeam();
        if (vehicleTeam != null && enemyTeam != null) {
            return !enemyTeam.isAlliedTo(vehicleTeam);
        }
        Entity driver = enemy.getDriver();
        return !(driver instanceof Player && gunner.isOwnedBy(driver));
    }

    /** 查找第一把可用反辐射弹（有弹、不在冷却/装填）。 */
    private static int findArmWeaponIndex(WeaponUnit weaponUnit) {
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
            AbstractVehicleWeapon<?> proxy = weaponUnit.proxyWeapon(weapon);
            if (!(proxy instanceof RVP_WeaponBase rvpWeapon)) {
                continue;
            }
            RVP_WeaponData data = rvpWeapon.getData();
            if (data == null || !data.isAntiRadiationMissile()) {
                continue;
            }
            if (proxy.hasAmmo() && !proxy.isCoolingDown() && !proxy.isReloading()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 发射一发反辐射弹到指定目标。复用 GunnerWeaponSuitability.prepareLaunchLock
     * （对反辐射弹内部会做 resolveArmPreselect：范围/高度/锥角/发射源有效）作为门控；
     * 受全导弹 5 秒冷却约束。返回是否实际发射。
     */
    private static boolean fireArmMissileAt(GunnerEntity gunner, WeaponUnit weaponUnit, Entity target) {
        if (gunner.getMissileCooldown() > 0) {
            return false;
        }
        int index = findArmWeaponIndex(weaponUnit);
        if (index < 0) {
            return false;
        }
        AbstractVehicleWeapon<?> arm = weaponUnit.getIndexedWeapons().get(index);
        if (!GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, arm, target)) {
            return false;
        }
        WeaponUnit aimSource = arm.getWeaponUnit();
        weaponUnit.shoot(index, Collections.singletonList(aimSource.aimContext()), gunner);
        gunner.setMissileCooldown(MISSILE_COOLDOWN_TICK);
        return true;
    }

    /** SEAD 复仇发射判定：复仇一发尚未打出、冷却结束且门控通过时发射。 */
    private static boolean tryFireRevenge(GunnerEntity gunner, WeaponUnit weaponUnit, Entity target) {
        if (gunner.isSeadRevengeFired()) {
            return false;
        }
        if (fireArmMissileAt(gunner, weaponUnit, target)) {
            gunner.setSeadRevengeFired(true);
            return true;
        }
        return false;
    }

    /** 退出 SEAD 复仇并清空相关状态，同时进入复仇冷却（防被持续锁定时无限循环）。 */
    private static void clearSead(GunnerEntity gunner) {
        gunner.setSeadMode(SEAD_NONE);
        gunner.setSeadTicks(0);
        gunner.setSeadRevengeTargetId(-1);
        gunner.setSeadImmediateFired(false);
        gunner.setSeadRevengeFired(false);
        gunner.setSeadCooldownTicks(SEAD_COOLDOWN_TICK);
    }

    private static int selectWeaponIndex(WeaponUnit weaponUnit, Entity target, @Nullable GunnerProfile profile) {
        Vec3 weaponPos = weaponUnit.worldPivotPosition();
        double dist = weaponPos.distanceTo(target.getBoundingBox().getCenter());
        boolean targetIsAmmo = target instanceof AmmoEntity;
        boolean targetHighAlt = altitudeAgl(target) >= 200.0;

        // GPS 武器优先：profile 启用 gps_prefer_farthest 时，索敌阶段已选中最远的 GPS 可打击目标，
        // 这里优先选定 GPS 武器发射，避免被武器索引顺序中更靠前的其它可用武器抢占
        if (profile != null && profile.isGpsPreferFarthest()) {
            for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
                AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
                AbstractVehicleWeapon<?> proxyWeapon = weaponUnit.proxyWeapon(weapon);
                if (proxyWeapon.hasAmmo()
                        && !proxyWeapon.isCoolingDown()
                        && !proxyWeapon.isReloading()
                        && !isCountermeasureWeapon(proxyWeapon)
                        && isGpsWeapon(proxyWeapon)
                        && GunnerWeaponSuitability.canSelectForTarget(weaponUnit, weapon, target)) {
                    return index;
                }
            }
        }

        // CIWS拦截弹药：200米外优先导弹，200米内优先机炮（近防拦截要快）
        if (targetIsAmmo) {
            if (dist > 200.0) {
                return findGuidedWeaponIndex(weaponUnit, target);
            }
            return findGunWeaponIndex(weaponUnit, target);
        }

        // 普通目标（含对地/对空载具）：优先制导武器——修复"gunner 爱用机炮"问题。
        // 只要目标能被任意 RVP 制导武器打到，就按武器类型打分选最优制导武器；机炮仅作兜底。
        int guided = findGuidedWeaponIndex(weaponUnit, target);
        if (guided >= 0) {
            return guided;
        }
        return findGunWeaponIndex(weaponUnit, target);
    }

    /**
     * 挑选可用制导武器：按"武器类型优先级"打分（ARM > IR/AIR > ARH/SARH > GPS > SACLOS/LBR/LH > HITL_TV），
     * 取类型优先级最高且能打到目标的那把；无可用制导武器返回 -1。
     */
    private static int findGuidedWeaponIndex(WeaponUnit weaponUnit, Entity target) {
        int bestIndex = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
            AbstractVehicleWeapon<?> proxyWeapon = weaponUnit.proxyWeapon(weapon);
            if (!proxyWeapon.hasAmmo()
                    || proxyWeapon.isCoolingDown()
                    || proxyWeapon.isReloading()
                    || isCountermeasureWeapon(proxyWeapon)) {
                continue;
            }
            int priority = guidedWeaponPriority(proxyWeapon);
            if (priority < 0) {
                continue;
            }
            if (priority >= bestPriority) {
                continue;
            }
            if (!GunnerWeaponSuitability.canSelectForTarget(weaponUnit, weapon, target)) {
                continue;
            }
            bestPriority = priority;
            bestIndex = index;
        }
        return bestIndex;
    }

    /**
     * 制导武器类型优先级（越小越优先）：GPS=1（远程点打击优先），ARM=2, IR/AIR=3,
     * ARH/SARH=4, SACLOS/LBR/LH=5, HITL_TV=6。非制导武器返回 -1（不参与优先选择）。
     */
    private static int guidedWeaponPriority(AbstractVehicleWeapon<?> weapon) {
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return -1;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data == null) {
            return -1;
        }
        if (data.isGpsMissile()) {
            return 1;
        }
        if (data.isAntiRadiationMissile()) {
            return 2;
        }
        if (data.usesGuidanceType(RVP_EnumGuidanceType.IR) || data.usesGuidanceType(RVP_EnumGuidanceType.AIR)) {
            return 3;
        }
        if (data.usesGuidanceType(RVP_EnumGuidanceType.ARH) || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)) {
            return 4;
        }
        if (data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SALH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LBR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LH)) {
            return 5;
        }
        if (data.usesGuidanceType(RVP_EnumGuidanceType.HITL_TV)
                || data.usesGuidanceType(RVP_EnumGuidanceType.HITL_CLOS_TV)) {
            return 6;
        }
        return -1;
    }

    /** 挑选可用机炮/非制导武器（近防或制导武器不可用时的兜底）。排除 RVP 制导导弹。 */
    private static int findGunWeaponIndex(WeaponUnit weaponUnit, Entity target) {
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
            AbstractVehicleWeapon<?> proxyWeapon = weaponUnit.proxyWeapon(weapon);
            if (proxyWeapon.hasAmmo()
                    && !proxyWeapon.isCoolingDown()
                    && !proxyWeapon.isReloading()
                    && !isCountermeasureWeapon(proxyWeapon)
                    && !isRvpHomingMissile(weaponUnit, weapon)
                    && GunnerWeaponSuitability.canSelectForTarget(weaponUnit, weapon, target)) {
                return index;
            }
        }
        return -1;
    }

    private static double altitudeAgl(Entity entity) {
        int groundY = entity.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.util.Mth.floor(entity.getX()),
                net.minecraft.util.Mth.floor(entity.getZ()));
        return entity.getY() - groundY;
    }

    private static boolean isCountermeasureWeapon(AbstractVehicleWeapon<?> weapon) {
        ResourceLocation weaponId = weapon.getData().getWeaponId();
        if (weaponId == null) {
            return false;
        }
        String path = weaponId.getPath();
        // 排除本体烟雾弹武器（launcher_smoke_grenade）：地面载具烟雾由 RVP countermeasure.smoke 承担，
        // 避免 gunner 同时放本体烟雾 + RVP 烟雾双份
        return path.contains("decoy_flare") || path.contains("aps_grenade");
    }

    /** 判断是否为 GPS 制导武器（GPS 为远程点打击武器，gunner 优先发射）。 */
    private static boolean isGpsWeapon(AbstractVehicleWeapon<?> weapon) {
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon)) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        return data != null && data.isGpsMissile();
    }

    /**
     * 供 RVP_GunnerDebugMonitor 使用，返回 selectWeaponIndex 的结果。
     * 仅在监控 dump 中指示是否有可用武器，不产生实际开火副作用。
     */
    static int findWeaponIndexForDump(WeaponUnit weaponUnit, Entity target) {
        return selectWeaponIndex(weaponUnit, target, null);
    }

    private static boolean isDriver(AbstractVehicle vehicle, GunnerEntity gunner) {
        if (vehicle.getDriver() == gunner) {
            return true;
        }
        for (Seat seat : vehicle.seats) {
            if (seat.passengerId == gunner.getId()) {
                return seat.seatIndex == 0;
            }
        }
        return false;
    }

    @Nullable
    private static WeaponUnit resolveWeaponUnit(AbstractVehicle vehicle, @Nullable PartUnit<?> seatUnit, boolean driver) {
        if (seatUnit instanceof WeaponUnit weaponUnit) {
            return weaponUnit;
        }
        if (!driver) {
            return null;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit && !weaponUnit.getIndexedWeapons().isEmpty()) {
                return weaponUnit;
            }
        }
        return null;
    }
}
