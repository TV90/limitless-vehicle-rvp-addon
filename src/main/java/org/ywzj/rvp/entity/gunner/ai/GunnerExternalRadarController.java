package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_ChaffJamState;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public final class GunnerExternalRadarController {

    private GunnerExternalRadarController() {}

    public static void tick(GunnerEntity gunner,
                            AbstractVehicle launcher,
                            @Nullable WeaponUnit weaponUnit,
                            @Nullable Entity target,
                            boolean driverAi) {
        if (!driverAi || launcher.level().isClientSide() || weaponUnit == null) {
            return;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            return;
        }

        AbstractVehicle relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher).orElse(null);
        if ((relayVehicle == null || relayVehicle.isRemoved() || !relayVehicle.isAlive() || relayVehicle.isDestroyed())
                && gunner.tickCount % 20 == 0) {
            RVP_DeployableUavService.deployLinkedUav(launcher, gunner);
            relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(launcher).orElse(null);
        }
        if (relayVehicle == null || relayVehicle.isRemoved() || !relayVehicle.isAlive() || relayVehicle.isDestroyed()) {
            clearExternalLock(root, null);
            return;
        }

        turnOnRelayRadars(relayVehicle);
        RadarUnit lockRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
        if (lockRadar == null) {
            clearExternalLock(root, relayVehicle);
            return;
        }

        Entity lockTarget = normalizeTarget(target);
        if (lockTarget == null || !lockTarget.isAlive()) {
            // gunner 索敌半径太小（默认 96 格），无自身雷达的发射车只能靠外置雷达：
            // 直接按中继雷达扫描范围找最近敌对载具作为锁定目标，保证 RWR 告警生效
            lockTarget = findRelayScanTarget(launcher, relayVehicle, lockRadar, gunner);
        }
        if (lockTarget == null || !lockTarget.isAlive() || !isWithinRelayLockVolume(lockRadar, lockTarget)) {
            clearExternalLock(root, relayVehicle);
            return;
        }

        lockRadar.detect(lockTarget);
        // 箔条禁锁期：目标被箔条干扰脱锁后短时间内不可被选中/锁定（仍可被扫描），
        // 否则炮手 AI 每 tick 重锁会令脱锁瞬间被还原，雷达看起来"怎么都脱不了锁"
        if (RVP_ChaffJamState.isInCooldown(lockTarget.getUUID(), launcher.level().getGameTime())) {
            clearExternalLock(root, relayVehicle);
            return;
        }
        if (!RVP_RadarRoleHelper.entityMatches(lockRadar.getLockedEntity(), lockTarget.getId())) {
            lockRadar.setLockedEntity(lockTarget);
        }
        if (!RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), lockTarget.getId())) {
            root.setLockedEntity(lockTarget);
        }
        RVP_WeaponLockStateTable.setExternalRadarRequestedEntityId(root, lockTarget.getId());
        RVP_WeaponLockStateTable.setExternalRadarLockedEntityId(root, lockTarget.getId());
    }

    private static void turnOnRelayRadars(AbstractVehicle relayVehicle) {
        if (relayVehicle.isDestroyed()) {
            return;
        }
        // 保证中继车供电：本体 RadarUnit.tickScan（RADAR_SEARCH 告警）与
        // WeaponUnit.tick（RADAR_LOCK 告警）都需要 hasPower
        if (!relayVehicle.isEngineOn()) {
            relayVehicle.toggleEngine(true);
        }
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && !radarUnit.isOn()) {
                radarUnit.toggle(true);
            }
        }
    }

    /**
     * 按中继雷达扫描范围（maxScanDistance，可达数千格）找最近敌对载具。
     * 绕开 gunner 默认 96 格索敌半径；锁定仅是雷达告警，不施加创造模式保护过滤。
     */
    @Nullable
    private static Entity findRelayScanTarget(AbstractVehicle launcher, AbstractVehicle relayVehicle,
                                              RadarUnit lockRadar, GunnerEntity gunner) {
        double maxRange = lockRadar.getMaxScanDistance();
        if (maxRange <= 0) {
            return null;
        }
        Vec3 radarPos = lockRadar.worldRadarPosition();
        if (!(relayVehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }
        // O(实体) 遍历已加载载具，替代 ±maxRange（可达数千格）立方体 getEntities（O(箱子截面)）
        Entity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle) || !vehicle.isAlive() || vehicle.isDestroyed()) {
                continue;
            }
            if (entity == launcher || entity == relayVehicle) {
                continue;
            }
            if (!isHostileRelayTarget(launcher, entity, gunner)) {
                continue;
            }
            Vec3 center = entity.getBoundingBox().getCenter();
            double distSqr = center.distanceToSqr(radarPos);
            if (distSqr > maxRange * maxRange) {
                continue;
            }
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = entity;
            }
        }
        return best;
    }

    /** 敌对判定：不同队且（ENEMY faction 无差别 / 非自己人载具）。 */
    private static boolean isHostileRelayTarget(AbstractVehicle launcher, Entity entity, GunnerEntity gunner) {
        Team launcherTeam = launcher.getTeam();
        Team targetTeam = entity.getTeam();
        if (launcherTeam != null && targetTeam != null && launcherTeam.isAlliedTo(targetTeam)) {
            return false;
        }
        if (gunner.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY && gunner.isOwnedBy(entity)) {
            return false;
        }
        return true;
    }

    @Nullable
    private static Entity normalizeTarget(@Nullable Entity target) {
        if (target instanceof Player player && player.getVehicle() instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        return target;
    }

    private static boolean isWithinRelayLockVolume(RadarUnit radarUnit, Entity target) {
        Vec3 radarPos = radarUnit.worldRadarPosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        double maxRange = radarUnit.getMaxScanDistance();
        if (targetPos.distanceToSqr(radarPos) > maxRange * maxRange) {
            return false;
        }
        if (!isWithinScanHeight(radarUnit, targetPos)) {
            return false;
        }
        Vec2 aimRot = radarUnit.aimRot(targetPos);
        float yMin = radarUnit.getYRotMin();
        float yMax = radarUnit.getYRotMax();
        float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
        if (!isYawWithin(y, yMin, yMax)) {
            return false;
        }
        // MC 约定负俯仰=仰角（目标在上方）。SAM 中继雷达 x_rot_min 常为 0 只允许向下扫描，
        // 会把高空目标（aimRot.x<0）全拒掉；这里放开到 ±90 让中继雷达能锁定上方目标。
        return Math.abs(aimRot.x) <= 90;
    }

    private static float getScanMinHeight(RadarUnit radarUnit) {
        RadarUnitData data = radarUnit.getData();
        return data instanceof RadarUnitDataExt ext ? ext.ywzj_rvp$getScanMinHeight() : 25f;
    }

    private static float getScanMaxHeight(RadarUnit radarUnit) {
        RadarUnitData data = radarUnit.getData();
        return data instanceof RadarUnitDataExt ext ? ext.ywzj_rvp$getScanMaxHeight() : 10000f;
    }

    private static boolean isWithinScanHeight(RadarUnit radarUnit, Vec3 targetPos) {
        float minHeight = 25f;
        float maxHeight = 10000f;
        RadarUnitData data = radarUnit.getData();
        if (data instanceof RadarUnitDataExt ext) {
            minHeight = ext.ywzj_rvp$getScanMinHeight();
            maxHeight = ext.ywzj_rvp$getScanMaxHeight();
        }
        if (maxHeight < minHeight) {
            float swap = minHeight;
            minHeight = maxHeight;
            maxHeight = swap;
        }
        int groundY = radarUnit.getVehicle().level().getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(targetPos.x),
                Mth.floor(targetPos.z));
        double agl = targetPos.y - groundY;
        return agl >= minHeight && agl <= maxHeight;
    }

    private static boolean isYawWithin(float y, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return true;
        }
        return y >= yMin && y <= yMax;
    }

    private static float normalizeYawForLimits(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return yaw;
        }
        boolean prefer360Space = yMin >= 0.0f && yMax > 180.0f;
        if (prefer360Space && yaw < 0.0f) {
            return yaw + 360.0f;
        }
        return yaw;
    }

    private static void clearExternalLock(WeaponUnit root,
                                          @Nullable AbstractVehicle relayVehicle) {
        int requestedId = RVP_WeaponLockStateTable.getExternalRadarRequestedEntityId(root);
        int lockedId = RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root);
        Entity localRadarLocked = RVP_RadarRoleHelper.getLockedRadarEntity(root);
        boolean localRadarKeepsLock = RVP_RadarRoleHelper.entityMatches(localRadarLocked, requestedId)
                || RVP_RadarRoleHelper.entityMatches(localRadarLocked, lockedId);
        if (!localRadarKeepsLock
                && (RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), requestedId)
                || RVP_RadarRoleHelper.entityMatches(root.getLockedEntity(), lockedId))) {
            root.setLockedEntity(null);
        }
        RVP_WeaponLockStateTable.clearExternalRadarRequestedEntityId(root);
        RVP_WeaponLockStateTable.clearExternalRadarLockedEntityId(root);
        if (relayVehicle == null) {
            return;
        }
        for (PartUnit<?> partUnit : relayVehicle.getPartUnits()) {
            if (partUnit instanceof RadarUnit radarUnit && radarUnit.getLockedEntity() != null) {
                radarUnit.setLockedEntity(null);
            }
        }
    }
}
