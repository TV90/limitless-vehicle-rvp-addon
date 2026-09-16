package org.ywzj.rvp.entity.gunner.behavior.action;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerEngagementNet;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerLockDebug;
import org.ywzj.rvp.entity.gunner.ai.GunnerTargeting;
import org.ywzj.rvp.entity.gunner.ai.GunnerWeaponSuitability;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleDecoyFlare;
import org.ywzj.vehicle.vehicle.weapon.VehicleGrenade;

import java.util.Collections;

/** 封装 Gunner 的瞄准、锁定/制导准备、发射与冷却推进事务。 */
public final class RVP_GunnerWeaponActions {

    /** 所有 RVP 制导武器发射后的统一冷却，单位 tick。 */
    private static final int MISSILE_COOLDOWN_TICK = 100;
    /** 发射事务使用的制导控制源适配器。 */
    private final RVP_GunnerGuidanceActions guidance;

    RVP_GunnerWeaponActions(RVP_GunnerGuidanceActions guidance) {
        this.guidance = guidance;
    }

    /**
     * 执行一次普通/CIWS 完整交战事务，保持阶段 A 的选择、门控与冷却语义。
     *
     * <p>本体 {@link WeaponUnit#shoot} 不返回真实发射结果，因此通过全部前置门控并调用后返回
     * DISPATCHED；该结果明确表示仍由本体武器舱、Forge 事件、弹药和武器冷却作最终裁决。</p>
     */
    public RVP_GunnerActionResult engage(GunnerEntity gunner,
                                         WeaponUnit weaponUnit,
                                         Entity target,
                                         GunnerProfile profile,
                                         boolean launcher) {
        if (gunner == null || weaponUnit == null || target == null || !target.isAlive() || profile == null) {
            return RVP_GunnerActionResult.INVALID;
        }
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        if (vehicle == null || vehicle.level().isClientSide()) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (!launcher) {
            Vec3 aimPoint = GunnerTargeting.predictAimPoint(weaponUnit.worldPivotPosition(), target)
                    .add(target.getDeltaMovement().scale(Math.max(0.0, profile.getLeadScale() - 1.0)));
            // 调用本体武器站瞄准 API，让炮塔在选弹和射界检查前更新目标姿态。
            weaponUnit.aim(aimPoint);
        }

        int weaponIndex = selectWeaponIndex(weaponUnit, target, profile);
        if (weaponIndex < 0) {
            gunner.setControlledWeaponIndex(-1);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logEngage(vehicle, target, "NO_WEAPON", "");
            }
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        gunner.setControlledWeaponIndex(weaponIndex);
        AbstractVehicleWeapon<?> selectedWeapon = weaponUnit.getIndexedWeapons().get(weaponIndex);
        boolean rvpMissile = isRvpHomingMissile(weaponUnit, selectedWeapon);
        boolean selfGuided = isSelfGuidedMissile(weaponUnit, selectedWeapon);
        boolean aircraftTarget = target instanceof FixedWingVehicle || target instanceof RotaryWingVehicle;

        // 对空导弹纪律：目标是飞机时，必须持锁满 5 秒（100 tick）才能发射；
        // 发射后 5 秒内不再对同一目标发射（目标切换时计时重置）
        if (rvpMissile && aircraftTarget) {
            int now = gunner.tickCount;
            if (gunner.getAirLockStartTick() == 0) {
                gunner.setAirLockStartTick(now);
            }
            if (now - gunner.getAirLockStartTick() < 100
                    || gunner.getLastAirMissileFireTick() != 0
                    && now - gunner.getLastAirMissileFireTick() < 100) {
                if (FMLEnvironment.dist == Dist.CLIENT) {
                    RVP_GunnerLockDebug.logEngage(vehicle, target, "AIR_DISCIPLINE",
                            String.format("hold=%d/100 refire=%s", now - gunner.getAirLockStartTick(),
                                    gunner.getLastAirMissileFireTick() != 0
                                            ? (now - gunner.getLastAirMissileFireTick()) + "/100" : "clear"));
                }
                return RVP_GunnerActionResult.GATED;
            }
        }
        if (rvpMissile && gunner.getMissileCooldown() > 0 || !gunner.isBurstWindowOpen()) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logEngage(vehicle, target, "COOLDOWN",
                        String.format("missileCD=%d burstOpen=%b", gunner.getMissileCooldown(), gunner.isBurstWindowOpen()));
            }
            return RVP_GunnerActionResult.GATED;
        }

        // 炮塔对准窗口：仅对非制导武器（机炮等）要求对准。RVP 制导武器发射后自行转向目标，
        // 不受炮塔旋转角度限制（垂发车辆本就跳过该检查）。
        if (!launcher && !rvpMissile) {
            float xError = Math.abs(Mth.wrapDegrees(weaponUnit.getXRot() - weaponUnit.getXAimRot()));
            float yError = Math.abs(Mth.wrapDegrees(weaponUnit.getYRot() - weaponUnit.getYAimRot()));
            if (xError > profile.getFireWindowDeg() || yError > profile.getFireWindowDeg()) {
                if (FMLEnvironment.dist == Dist.CLIENT) {
                    RVP_GunnerLockDebug.logEngage(vehicle, target, "AIM_WINDOW",
                            String.format("err=%.1f,%.1f win=%.1f", xError, yError, profile.getFireWindowDeg()));
                }
                return RVP_GunnerActionResult.GATED;
            }
        }

        // 调用项目武器适配性入口，在发射事务内完成 RF/IR/GPS/AntiRadiation 等锁定准备。
        if (!GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, selectedWeapon, target)) {
            // 制导武器无法发射（如缺雷达/锁不上/锥角不足）时回退到机炮等非制导武器，
            // 避免 gunner 卡死在"选中导弹但打不出"而不作战
            if (!rvpMissile) {
                if (FMLEnvironment.dist == Dist.CLIENT) {
                    RVP_GunnerLockDebug.logEngage(vehicle, target, "LOCK_PREPARE_FAIL",
                            "weapon=" + weaponTag(selectedWeapon));
                }
                return RVP_GunnerActionResult.GATED;
            }
            int fallback = findGunWeaponIndex(weaponUnit, target);
            if (fallback < 0 || fallback == weaponIndex) {
                if (FMLEnvironment.dist == Dist.CLIENT) {
                    RVP_GunnerLockDebug.logEngage(vehicle, target, "LOCK_PREPARE_FAIL",
                            "missile无备选机炮 weapon=" + weaponTag(selectedWeapon));
                }
                return RVP_GunnerActionResult.GATED;
            }
            selectedWeapon = weaponUnit.getIndexedWeapons().get(fallback);
            weaponIndex = fallback;
            gunner.setControlledWeaponIndex(fallback);
            // 调用同一项目锁定准备入口，保证回退武器也经过完整门控。
            if (!GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, selectedWeapon, target)) {
                if (FMLEnvironment.dist == Dist.CLIENT) {
                    RVP_GunnerLockDebug.logEngage(vehicle, target, "LOCK_PREPARE_FAIL",
                            "fallback=" + weaponTag(selectedWeapon));
                }
                return RVP_GunnerActionResult.GATED;
            }
        }

        // 调用制导动作适配器，在本体 shoot 前准备本发 GPS/照射/HITL 控制源。
        guidance.prepareLaunch(gunner, vehicle, weaponUnit, selectedWeapon, target);
        // 使用实际发射武器站的 aimContexts（对 VehicleWeaponAgent 而言是目标武器站，如 launcher_weapon）
        WeaponUnit aimSource = selectedWeapon.getWeaponUnit();
        // 与手动发射保持一致：RIPPLE（轮射）只传当前管位 1 个瞄准上下文，SALVO（齐射）才一次全部发射。
        // 此前非垂发车辆一律传 aimSource.aimContexts()，多管发射架（如 cssa5/ps1sm 的 missile_barrel 有 2 根管）
        // 会一次把多管全打出去；垂发车辆也保留单上下文特判。
        boolean singleContext = launcher || aimSource.getFiringMode() == WeaponUnitData.FiringMode.RIPPLE;
        // 调用本体权威发射链，继续保留武器舱、Forge 事件、弹药与网络广播语义。
        weaponUnit.shoot(weaponIndex,
                singleContext ? Collections.singletonList(aimSource.aimContext()) : aimSource.aimContexts(), gunner);
        gunner.onBurstShot(launcher ? 1 : profile.getBurstFireTick(), profile.getBurstRestTick());
        if (rvpMissile) {
            gunner.setMissileCooldown(MISSILE_COOLDOWN_TICK);
            if (aircraftTarget) {
                gunner.setLastAirMissileFireTick(gunner.tickCount);
            }
        }
        if (selfGuided && target instanceof AmmoEntity) {
            gunner.setCiwsTargetCooldown(target, 100);
        }
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RVP_GunnerLockDebug.logEngage(vehicle, target, "FIRED", "weapon=" + weaponTag(selectedWeapon));
        }
        // 调用组网窗口策略，让发射记账与新目标跟踪记账使用相同的距离滑动窗口。
        long engagementWindowTick = RVP_GunnerEngagementNet.resolveWindowTick(
                vehicle, target, profile.getEngagementNetCooldownTick());
        if (engagementWindowTick > 0L) {
            // 调用本项目组网表记录已进入本体发射链的目标，同时启动排斥窗口与 60 tick 限位窗口。
            RVP_GunnerEngagementNet.markEngaged(vehicle.level(), gunner.getProfileFaction(), target,
                    gunner, engagementWindowTick);
        }
        return RVP_GunnerActionResult.DISPATCHED;
    }

    /** 尝试由本体式反制武器瞄准并拦截危险弹药。 */
    public RVP_GunnerActionResult fireCountermeasure(GunnerEntity gunner,
                                                     AbstractVehicle vehicle,
                                                     AmmoEntity threat) {
        if (gunner == null || vehicle == null || threat == null || !threat.isAlive()) {
            return RVP_GunnerActionResult.INVALID;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
                AbstractVehicleWeapon<?> rawWeapon = weaponUnit.getIndexedWeapons().get(index);
                AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
                if (!isCountermeasureWeapon(weapon) || !weapon.hasAmmo()
                        || weapon.isCoolingDown() || weapon.isReloading()) {
                    continue;
                }
                // 调用本体武器站瞄准 API，使反制武器指向来袭弹药。
                weaponUnit.aim(threat.position());
                // 调用本体权威发射链；实际成功仍由武器自身和 Forge 事件裁决。
                weaponUnit.shoot(index, weaponUnit.aimContexts(), gunner);
                return RVP_GunnerActionResult.DISPATCHED;
            }
        }
        return RVP_GunnerActionResult.UNSUPPORTED;
    }

    /** 调试日志用的武器标识（weaponId，缺失时回退类名）。 */
    private static String weaponTag(AbstractVehicleWeapon<?> weapon) {
        if (weapon.getData() != null && weapon.getData().getWeaponId() != null) {
            return weapon.getData().getWeaponId().toString();
        }
        return weapon.getClass().getSimpleName();
    }

    /** 尝试向指定雷达辐射源发射一枚 AntiRadiation 武器。 */
    public RVP_GunnerActionResult fireAntiRadiation(GunnerEntity gunner,
                                                    WeaponUnit weaponUnit,
                                                    Entity target) {
        if (gunner == null || weaponUnit == null || target == null || !target.isAlive()) {
            return RVP_GunnerActionResult.INVALID;
        }
        if (gunner.getMissileCooldown() > 0) {
            return RVP_GunnerActionResult.GATED;
        }
        int index = findAntiRadiationWeaponIndex(weaponUnit);
        if (index < 0) {
            return RVP_GunnerActionResult.UNSUPPORTED;
        }
        AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
        // 调用项目武器适配性入口，完成 AntiRadiation 发射源预选与包线门控。
        if (!GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, weapon, target)) {
            return RVP_GunnerActionResult.GATED;
        }
        // 调用制导动作适配器，保持特殊武器的发射前控制源准备边界一致。
        guidance.prepareLaunch(gunner, weaponUnit.getVehicle(), weaponUnit, weapon, target);
        WeaponUnit aimSource = weapon.getWeaponUnit();
        // 调用本体权威发射链；AntiRadiation 维持阶段 A 的单管上下文语义。
        weaponUnit.shoot(index, Collections.singletonList(aimSource.aimContext()), gunner);
        gunner.setMissileCooldown(MISSILE_COOLDOWN_TICK);
        return RVP_GunnerActionResult.DISPATCHED;
    }

    /** 查找第一把当前可用的 AntiRadiation 武器。 */
    public int findAntiRadiationWeaponIndex(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return -1;
        }
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> weapon = weaponUnit.getIndexedWeapons().get(index);
            AbstractVehicleWeapon<?> proxy = weaponUnit.proxyWeapon(weapon);
            if (!(proxy instanceof RVP_WeaponBase rvpWeapon)) {
                continue;
            }
            RVP_WeaponData data = rvpWeapon.getData();
            if (data != null && data.isAntiRadiationMissile()
                    && proxy.hasAmmo() && !proxy.isCoolingDown() && !proxy.isReloading()) {
                return index;
            }
        }
        return -1;
    }

    /** 判断载具是否还有非反制用途的作战弹药。 */
    public boolean hasCombatAmmo(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return false;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> rawWeapon : weaponUnit.getIndexedWeapons()) {
                AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
                if (!isCountermeasureWeapon(weapon) && weapon.hasAmmo()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 供调试监控只读查询当前会选择的武器索引。 */
    public int findWeaponIndex(WeaponUnit weaponUnit, Entity target, @Nullable GunnerProfile profile) {
        return selectWeaponIndex(weaponUnit, target, profile);
    }

    /** 按当前目标与 Profile 选择武器。 */
    private static int selectWeaponIndex(WeaponUnit weaponUnit, Entity target, @Nullable GunnerProfile profile) {
        Vec3 weaponPosition = weaponUnit.worldPivotPosition();
        double distance = weaponPosition.distanceTo(target.getBoundingBox().getCenter());
        boolean targetIsAmmo = target instanceof AmmoEntity;

        if (profile != null && profile.isGpsPreferFarthest()) {
            for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
                AbstractVehicleWeapon<?> rawWeapon = weaponUnit.getIndexedWeapons().get(index);
                AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
                if (weapon.hasAmmo() && !weapon.isCoolingDown() && !weapon.isReloading()
                        && !isCountermeasureWeapon(weapon) && isGpsWeapon(weapon)
                        && GunnerWeaponSuitability.canSelectForTarget(weaponUnit, rawWeapon, target)) {
                    return index;
                }
            }
        }
        if (targetIsAmmo) {
            return distance > 200.0
                    ? findGuidedWeaponIndex(weaponUnit, target) : findGunWeaponIndex(weaponUnit, target);
        }
        int guided = findGuidedWeaponIndex(weaponUnit, target);
        return guided >= 0 ? guided : findGunWeaponIndex(weaponUnit, target);
    }

    /** 查找优先级最高且可用的制导武器。 */
    private static int findGuidedWeaponIndex(WeaponUnit weaponUnit, Entity target) {
        int bestIndex = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> rawWeapon = weaponUnit.getIndexedWeapons().get(index);
            AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
            if (!weapon.hasAmmo() || weapon.isCoolingDown() || weapon.isReloading()
                    || isCountermeasureWeapon(weapon)) {
                continue;
            }
            int priority = guidedWeaponPriority(weapon);
            if (priority >= 0 && priority < bestPriority
                    && GunnerWeaponSuitability.canSelectForTarget(weaponUnit, rawWeapon, target)) {
                bestPriority = priority;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    /** 返回制导武器优先级，数值越小越优先，非制导返回 -1。 */
    private static int guidedWeaponPriority(AbstractVehicleWeapon<?> weapon) {
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon) || rvpWeapon.getData() == null) {
            return -1;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        if (data.isGpsMissile()) return 1;
        if (data.isAntiRadiationMissile()) return 2;
        if (data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.AIR)) return 3;
        if (data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)) return 4;
        if (data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SALH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LBR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LH)) return 5;
        if (data.usesGuidanceType(RVP_EnumGuidanceType.HITL_TV)
                || data.usesGuidanceType(RVP_EnumGuidanceType.HITL_CLOS_TV)) return 6;
        return -1;
    }

    /** 查找第一把可用的非制导作战武器。 */
    private static int findGunWeaponIndex(WeaponUnit weaponUnit, Entity target) {
        for (int index = 0; index < weaponUnit.getIndexedWeapons().size(); index++) {
            AbstractVehicleWeapon<?> rawWeapon = weaponUnit.getIndexedWeapons().get(index);
            AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
            if (weapon.hasAmmo() && !weapon.isCoolingDown() && !weapon.isReloading()
                    && !isCountermeasureWeapon(weapon)
                    && !isRvpHomingMissile(weaponUnit, rawWeapon)
                    && GunnerWeaponSuitability.canSelectForTarget(weaponUnit, rawWeapon, target)) {
                return index;
            }
        }
        return -1;
    }

    /** 通过本体武器运行时类型和 grenade 数据能力识别反制武器，不匹配武器 ID。 */
    private static boolean isCountermeasureWeapon(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleDecoyFlare) {
            return true;
        }
        return weapon instanceof VehicleGrenade grenade
                && "aps".equals(grenade.getData().getGrenade());
    }

    /** 判断武器是否属于需统一导弹冷却的 RVP 制导武器。 */
    private static boolean isRvpHomingMissile(WeaponUnit weaponUnit, AbstractVehicleWeapon<?> rawWeapon) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon) || rvpWeapon.getData() == null) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        return data.isHomingProjectile()
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.AIR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)
                || data.usesGuidanceType(RVP_EnumGuidanceType.SALH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.LBR);
    }

    /** 判断导弹是否能在发射后自行维持对 CIWS 目标的跟踪。 */
    private static boolean isSelfGuidedMissile(WeaponUnit weaponUnit, AbstractVehicleWeapon<?> rawWeapon) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.proxyWeapon(rawWeapon);
        if (!(weapon instanceof RVP_WeaponBase rvpWeapon) || rvpWeapon.getData() == null) {
            return false;
        }
        RVP_WeaponData data = rvpWeapon.getData();
        return data.isHomingProjectile()
                || data.usesGuidanceType(RVP_EnumGuidanceType.SARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.IR)
                || data.usesGuidanceType(RVP_EnumGuidanceType.AIR);
    }

    /** 判断武器是否使用 GPS 制导。 */
    private static boolean isGpsWeapon(AbstractVehicleWeapon<?> weapon) {
        return weapon instanceof RVP_WeaponBase rvpWeapon
                && rvpWeapon.getData() != null && rvpWeapon.getData().isGpsMissile();
    }
}
