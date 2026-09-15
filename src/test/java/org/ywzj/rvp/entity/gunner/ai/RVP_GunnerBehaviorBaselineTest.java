package org.ywzj.rvp.entity.gunner.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gunner 渐进式重构阶段 A 的特征测试。
 *
 * <p>当前 Gunner 直接依赖 Minecraft/Forge 实体和本体载具运行时，普通 JUnit 无法可靠构造完整世界。
 * 本测试因此冻结现行权威入口的可观察调用顺序、控制输出、门控常量和清理语义。阶段 B 开始移动
 * 生产逻辑时，应先让新的动作层测试覆盖相同契约，再调整这里的源码入口断言，禁止直接删除基线。</p>
 */
class RVP_GunnerBehaviorBaselineTest {

    /** Gunner 总编排源码，用于冻结当前服务端行为顺序。 */
    private static final Path BRAIN_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerBrain.java");
    /** Gunner 实体源码，用于冻结生命周期、离座和运行时状态清理。 */
    private static final Path ENTITY_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/GunnerEntity.java");
    /** Gunner 索敌源码，用于冻结目标层级、范围和扫描方式。 */
    private static final Path TARGETING_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerTargeting.java");
    /** Gunner 组网交战表源码，用于冻结排斥窗口、硬禁截止和交战者豁免语义。 */
    private static final Path ENGAGEMENT_NET_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/RVP_GunnerEngagementNet.java");
    /** Gunner 外置雷达源码，用于冻结中继部署、锁定和清理顺序。 */
    private static final Path EXTERNAL_RADAR_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerExternalRadarController.java");
    /** Gunner 制导控制源码，用于冻结 GPS、照射与 HITL 维护入口。 */
    private static final Path GUIDANCE_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/GunnerGuidedWeaponController.java");
    /** 阶段 B 武器动作源码，用于冻结原子交战事务。 */
    private static final Path WEAPON_ACTION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerWeaponActions.java");
    /** 阶段 B 移动动作源码，用于冻结唯一 reset + apply 边界。 */
    private static final Path MOVEMENT_ACTION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerMovementActions.java");
    /** 阶段 B 雷达动作源码，用于冻结本车锁定事务。 */
    private static final Path RADAR_ACTION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerRadarActions.java");
    /** 阶段 B 制导动作源码，用于冻结维护与发射准备两条边界。 */
    private static final Path GUIDANCE_ACTION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerGuidanceActions.java");
    /** 阶段 B 防御动作源码，用于冻结干扰物与 ECM 写入边界。 */
    private static final Path DEFENSE_ACTION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerDefenseActions.java");
    /** 阶段 B 动作网关源码，用于冻结六个领域适配器的聚合入口。 */
    private static final Path ACTION_GATEWAY_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerActionGateway.java");
    /** 阶段 B 补给动作源码，用于冻结反射兼容的唯一归属。 */
    private static final Path SUPPLY_ACTION_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/behavior/action/RVP_GunnerSupplyActions.java");
    /** Gunner 载具低频服务源码，用于冻结自动反制与同步周期。 */
    private static final Path VEHICLE_SERVICE_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/RVP_GunnerVehicleTickService.java");
    /** Gunner Profile 源码，用于冻结阶段 A 的平铺 schema 与默认值。 */
    private static final Path PROFILE_SOURCE = Path.of(
            "src/main/java/org/ywzj/rvp/entity/gunner/ai/profile/GunnerProfile.java");

    @Test
    void serverTickPipelineKeepsCurrentAuthoritativeOrder() throws IOException {
        String brain = read(BRAIN_SOURCE);
        String tick = section(brain,
                "public static void tick(GunnerEntity gunner, AbstractVehicle vehicle)",
                "private static Entity tickTargeting(");

        assertOrdered(tick,
                "gunner.tickCooldowns();",
                "vehicle.getOwnOperatorUnit(gunner)",
                "boolean driver = isDriver(vehicle, gunner);",
                "resolveWeaponUnit(vehicle, seatUnit, driver)",
                "tickTargeting(gunner, vehicle, weaponUnit, profile)",
                "ACTIONS.supply().refillOnDriverEnter(gunner, vehicle);",
                "ACTIONS.supply().sustainDriverAmmo(gunner, vehicle);",
                "tickCountermeasure(gunner, vehicle, profile);",
                "tickEcmActive(gunner, vehicle);",
                "tickSmokeEvasion(gunner, vehicle);",
                "ACTIONS.radar().maintainLocalLock(vehicle, weaponUnit, target);",
                "ACTIONS.radar().maintainExternalLock(gunner, vehicle, weaponUnit, target, driverAi);",
                "ACTIONS.guidance().maintain(gunner, vehicle, weaponUnit, target);",
                "seadHandled = tickSead(gunner, vehicle, weaponUnit, profile);",
                "allowFire = tickDriving(gunner, vehicle, target, profile);",
                "tickCombat(gunner, weaponUnit, target, profile);");
        assertContainsAll(tick,
                "boolean driverAi = driver && profile.isAllowDrive();",
                "if (!seadHandled)",
                "gunner.setControlledWeaponIndex(-1);",
                "ACTIONS.movement().apply(gunner, vehicle, ACTIONS.movement().stopCommand());",
                "gunner.clearDriverRideState();");
        assertFalse(tick.contains(".shoot("), "阶段 B 后总编排不得绕过武器动作层");
        assertFalse(brain.contains("controlUnit."), "阶段 B 后 GunnerBrain 不得直接写 ControlUnit");
    }

    @Test
    void targetingKeepsCiwsPreemptionAndCurrentTierOrder() throws IOException {
        String brain = read(BRAIN_SOURCE);
        String targeting = read(TARGETING_SOURCE);
        String tickTargeting = section(brain,
                "private static Entity tickTargeting(",
                "private static boolean isCiwsAltitudeMet(");
        String findBest = section(targeting,
                "public static Entity findBestTarget(",
                "private static Entity pickBestInTier(");
        String ciws = section(targeting,
                "public static AmmoEntity findCiwsTarget(",
                "public static List<Entity> collectTargetEntities(");

        assertOrdered(tickTargeting,
                "GunnerTargeting.findCiwsTarget(gunner, vehicle)",
                "markEngagementNetOnTrack(gunner, vehicle, ciwsTarget, profile);",
                "gunner.setTrackedTarget(ciwsTarget);",
                "gunner.tickCount % profile.getScanIntervalTick() == 0",
                "GunnerTargeting.findBestTarget(gunner, vehicle, weaponUnit, profile)",
                "markEngagementNetOnTrack(gunner, vehicle, best, profile);",
                "gunner.getTrackedTarget()");
        assertContainsAll(tickTargeting,
                "tracked == null || !tracked.isAlive()",
                "gunner.setTrackedTarget(null);");
        assertContainsAll(brain,
                "target.getId() == gunner.getTrackedTargetId()",
                "RVP_GunnerEngagementNet.markTracked(");

        assertOrdered(findBest,
                "collectTargetEntities(vehicle, radius",
                "List<Entity> rvpAmmo",
                "RVP_GunnerEngagementNet.isHardLockedFor(",
                "RVP_GunnerEngagementNet.isRecentlyEngaged(",
                "List<Entity> ewDecoys",
                "List<Entity> hostileGunnerVehicles",
                "List<Entity> playerTargets",
                "List<Entity> fallbackTargets",
                "return pickBestInTier(gunner, vehicle, weaponUnit, profile, fallbackTargets, launcher);");
        assertContainsAll(targeting,
                "private static final double AIR_SEARCH_MULTIPLIER = 6.0;",
                "return base * AIR_SEARCH_MULTIPLIER;",
                "return Math.max(base, radarRange);",
                "profile.isGpsPreferFarthest()",
                "GunnerWeaponSuitability.hasUsableGpsWeaponForTarget",
                "score(vehicle, weaponUnit, entity, launcher)");
        assertContainsAll(ciws,
                "candidates.removeIf(entity -> RVP_GunnerEngagementNet.isHardLockedFor(",
                "RVP_GunnerEngagementNet.isRecentlyEngaged(",
                "freshCandidates.isEmpty() ? candidates : freshCandidates");
        String creativeProtection = section(targeting,
                "private static boolean isProtectedCreativePlayer(",
                "private static boolean hasProtectedCreativePassenger(");
        assertContainsAll(creativeProtection, "player.isSpectator()", "return player.isCreative();");
        assertFalse(creativeProtection.contains("Difficulty.HARD"),
                "创造模式免攻击不应随难度改变");
    }

    @Test
    void weaponEngagementKeepsAimLockGuidanceFireTransactionOrder() throws IOException {
        String weaponActions = read(WEAPON_ACTION_SOURCE);
        String combat = section(weaponActions,
                "public RVP_GunnerActionResult engage(",
                "public RVP_GunnerActionResult fireCountermeasure(");
        String selection = section(weaponActions,
                "private static int selectWeaponIndex(",
                "private static int findGuidedWeaponIndex(");
        String priority = section(weaponActions,
                "private static int guidedWeaponPriority(",
                "private static int findGunWeaponIndex(");

        assertOrdered(combat,
                "weaponUnit.aim(aimPoint);",
                "selectWeaponIndex(weaponUnit, target, profile)",
                "gunner.setControlledWeaponIndex(weaponIndex);",
                "gunner.getMissileCooldown() > 0",
                "gunner.isBurstWindowOpen()",
                "GunnerWeaponSuitability.prepareLaunchLock(weaponUnit, selectedWeapon, target)",
                "findGunWeaponIndex(weaponUnit, target)",
                "guidance.prepareLaunch",
                "weaponUnit.shoot(weaponIndex",
                "gunner.onBurstShot",
                "gunner.setMissileCooldown(MISSILE_COOLDOWN_TICK);",
                "RVP_GunnerEngagementNet.resolveWindowTick(",
                "RVP_GunnerEngagementNet.markEngaged(");
        assertContainsAll(combat,
                "now - gunner.getAirLockStartTick() < 100",
                "now - gunner.getLastAirMissileFireTick() < 100",
                "aimSource.getFiringMode() == WeaponUnitData.FiringMode.RIPPLE",
                "Collections.singletonList(aimSource.aimContext()) : aimSource.aimContexts()",
                "gunner.setCiwsTargetCooldown(target, 100);",
                "profile.getEngagementNetCooldownTick()");

        assertOrdered(selection,
                "profile.isGpsPreferFarthest()",
                "if (targetIsAmmo)",
                "distance > 200.0",
                "findGuidedWeaponIndex(weaponUnit, target)",
                "findGunWeaponIndex(weaponUnit, target)");
        assertContainsAll(weaponActions,
                "private static final int MISSILE_COOLDOWN_TICK = 100;");
        assertOrdered(priority,
                "if (data.isGpsMissile())",
                "return 1;",
                "if (data.isAntiRadiationMissile())",
                "return 2;",
                "RVP_EnumGuidanceType.IR",
                "return 3;",
                "RVP_EnumGuidanceType.ARH",
                "return 4;",
                "RVP_EnumGuidanceType.SACLOS",
                "return 5;",
                "RVP_EnumGuidanceType.HITL_TV",
                "return 6;");
        assertContainsAll(priority,
                "data.isGpsMissile()",
                "data.isAntiRadiationMissile()",
                "RVP_EnumGuidanceType.IR",
                "RVP_EnumGuidanceType.AIR",
                "RVP_EnumGuidanceType.ARH",
                "RVP_EnumGuidanceType.SARH",
                "RVP_EnumGuidanceType.SACLOS",
                "RVP_EnumGuidanceType.SALH",
                "RVP_EnumGuidanceType.LBR",
                "RVP_EnumGuidanceType.LH",
                "RVP_EnumGuidanceType.HITL_TV",
                "RVP_EnumGuidanceType.HITL_CLOS_TV");
    }

    @Test
    void movementKeepsCurrentVehicleDispatchAndControlOutputs() throws IOException {
        String brain = read(BRAIN_SOURCE);
        String movementActions = read(MOVEMENT_ACTION_SOURCE);
        String dispatch = section(brain,
                "private static boolean tickDriving(",
                "public static boolean hasLauncherDeployConfig(");
        String launcher = section(brain,
                "private static void tickLauncherGroundDriving(",
                "private static boolean hasAnyAmmo(");
        String ground = section(brain,
                "private static void tickGroundDriving(",
                "private static void tickGroundTacticalEvade(");
        String fixedWing = section(brain,
                "private static boolean tickFixedWingDriving(",
                "private static boolean tickRotaryDriving(");
        String rotaryWing = section(brain,
                "private static boolean tickRotaryDriving(",
                "private static double pickCruiseAgl(");
        String wander = section(brain,
                "private static void tickGroundWander(",
                "private static boolean ensureAirPhase(");

        assertOrdered(dispatch,
                "ACTIONS.movement().stopCommand();",
                "vehicle instanceof FixedWingVehicle",
                "tickFixedWingDriving",
                "ACTIONS.movement().apply(gunner, vehicle, command);",
                "vehicle instanceof RotaryWingVehicle",
                "tickRotaryDriving",
                "hasLauncherDeployConfig(vehicle)",
                "tickLauncherGroundDriving",
                "tickGroundDriving");

        assertContainsAll(launcher,
                "if (hasAmmo)",
                "tickGroundWander(gunner, vehicle, profile, command);",
                "command.backward = true;",
                "command.forward = true;");
        assertContainsAll(ground,
                "gunner.hasSmokeHoldTicks()",
                "tickSmokeHoldDrive(gunner, vehicle, command);",
                "tickGroundWander(gunner, vehicle, profile, command);",
                "gunner.startTacticalHold(GROUND_TACTICAL_HOLD_TICK);",
                "gunner.startRecovery(profile.getDriveRecoveryTick());",
                "command.backward = true;",
                "tickGroundTacticalEvade",
                "command.forward = true;");
        assertContainsAll(fixedWing,
                "tickFixedWingCruise(gunner, vehicle, profile, false, command);",
                "return false;",
                "ensureAirPhase(gunner, vehicle, profile)",
                "boolean breakAway",
                "command.yRot = desiredRot.y;",
                "command.forward = true;");
        assertContainsAll(rotaryWing,
                "command.up = true;",
                "ensureRotaryAirPhase(gunner, profile)",
                "command.yRot = facingRot.y;",
                "command.xRot = desiredPitch;");
        assertContainsAll(wander,
                "profile.isGroundWanderEnabled()",
                "gunner.setGroundBigTurnTargetYaw",
                "command.forward = true;",
                "command.left = true;",
                "command.right = true;");
        assertOrdered(movementActions,
                "control.reset();",
                "control.forward = command.forward;",
                "control.backward = command.backward;",
                "control.xRot = command.xRot;",
                "control.yRot = command.yRot;");
    }

    @Test
    void radarGuidanceAndDefenseKeepCurrentSupportSemantics() throws IOException {
        String brain = read(BRAIN_SOURCE);
        String radar = read(RADAR_ACTION_SOURCE);
        String defense = read(DEFENSE_ACTION_SOURCE);
        String countermeasure = section(brain,
                "private static void tickCountermeasure(",
                "private static final Map<Integer, Long> GUNNER_ECM_DBG");
        String ecm = section(brain,
                "private static void tickEcmActive(",
                "private static boolean tickSead(");
        String smoke = section(brain,
                "private static void tickSmokeEvasion(",
                "private record MissileScanResult(");
        String guidance = read(GUIDANCE_SOURCE);
        String externalRadar = read(EXTERNAL_RADAR_SOURCE);
        String vehicleService = read(VEHICLE_SERVICE_SOURCE);

        assertOrdered(radar,
                "prepareLockRadar(weaponUnit)",
                "radar.detect(lockTarget);",
                "RVP_ChaffJamState.isInCooldown",
                "radar.setLockedEntity(lockTarget);",
                "root.setLockedEntity(lockTarget);");
        assertOrdered(countermeasure,
                "GunnerTargeting.findAmmoThreat",
                "ACTIONS.defense().fireBaseCountermeasure(gunner, vehicle, threat)",
                "gunner.setCountermeasureCooldown");
        assertContainsAll(smoke,
                "RVP_EnumCountermeasureType.SMOKE",
                "% 10 != 0",
                "scanMissileThreats",
                "findLasingEnemy",
                "ACTIONS.defense()",
                ".fireCountermeasure(vehicle, RVP_EnumCountermeasureType.SMOKE)",
                "gunner.setSmokeHoldTicks(SMOKE_HOLD_TICKS);");
        assertContainsAll(ecm,
                "WarnType.RADAR_LOCK",
                "WarnType.MISSILE_LAUNCH",
                "GunnerTargeting.findAmmoThreat",
                "ACTIONS.defense().fireActiveEcm(vehicle);");
        assertContainsAll(defense,
                "weapons.fireCountermeasure",
                "RVP_CountermeasureRuntimeManager.fire(vehicle, type);",
                "RVP_EcmActiveManager.tryFireForVehicle(vehicle)");

        assertOrdered(guidance,
                "updateDesignation(gunner, vehicle, weaponUnit, targetPoint);",
                "updateGpsTarget(gunner, vehicle, weaponUnit, targetPoint);",
                "updateInFlightHitl(gunner, vehicle, target, targetPoint);");
        assertContainsAll(guidance,
                "GPSTargetManager.set",
                "RVP_SaclosOperatorSession.setDesignation",
                "missile.rvp$setHitlDesignatedEntity(target);",
                "missile.rvp$setHitlSteeringInput",
                "private static final double HITL_CONTROL_SEARCH_RANGE = 4096.0D;");

        assertOrdered(externalRadar,
                "RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle",
                "RVP_DeployableUavService.deployLinkedUav",
                "turnOnRelayRadars(relayVehicle);",
                "getPreferredRelayLockRadar",
                "lockRadar.detect(lockTarget);",
                "RVP_ChaffJamState.isInCooldown",
                "RVP_WeaponLockStateTable.setExternalRadarRequestedEntityId",
                "RVP_WeaponLockStateTable.setExternalRadarLockedEntityId");
        assertContainsAll(vehicleService,
                "private static final int TICK_INTERVAL = 5;",
                "private static final long AUTO_CM_INTERVAL_TICK = 100L;",
                "tickAutoCountermeasure(vehicle);",
                "RVP_EnumCountermeasureType.FLARE",
                "RVP_EnumCountermeasureType.CHAFF",
                "syncFactionToPlayers");
    }

    @Test
    void seadKeepsCurrentPreemptionStateMachineAndTiming() throws IOException {
        String brain = read(BRAIN_SOURCE);
        String weaponActions = read(WEAPON_ACTION_SOURCE);
        String sead = section(brain,
                "private static boolean tickSead(",
                "private static void tickSeadFly(");
        String armFire = section(weaponActions,
                "public RVP_GunnerActionResult fireAntiRadiation(",
                "public int findAntiRadiationWeaponIndex(");
        String clear = section(brain,
                "private static void clearSead(",
                "static int findWeaponIndexForDump(");

        assertContainsAll(brain,
                "private static final int SEAD_FLY_AWAY_TICK = 100;",
                "private static final int SEAD_REVERSAL_TICK = 160;",
                "private static final int SEAD_LOCK_FIRE_TICK = 40;",
                "private static final int SEAD_TIMEOUT_TICK = 400;",
                "private static final int SEAD_COOLDOWN_TICK = 400;",
                "private static final int SEAD_THREAT_SCAN_INTERVAL = 10;",
                "private static final double SEAD_RADAR_LOCK_RANGE = 1024.0;");
        assertOrdered(sead,
                "findRadarLockingEntity(gunner, vehicle)",
                "findAntiRadiationWeaponIndex(weaponUnit)",
                ".fireAntiRadiation(gunner, weaponUnit, radarSource)",
                "fireCountermeasure(vehicle, RVP_EnumCountermeasureType.CHAFF)",
                "gunner.setSeadMode(SEAD_FLY_AWAY);",
                "gunner.setSeadTotalTicks(gunner.getSeadTotalTicks() + 1);",
                "gunner.getSeadTotalTicks() > SEAD_TIMEOUT_TICK",
                "case SEAD_FLY_AWAY:",
                "case SEAD_REVERSAL:",
                "case SEAD_LOCK_FIRE:",
                "tryFireRevenge(gunner, weaponUnit, revengeTarget)");
        assertOrdered(armFire,
                "gunner.getMissileCooldown() > 0",
                "findAntiRadiationWeaponIndex(weaponUnit)",
                "GunnerWeaponSuitability.prepareLaunchLock",
                "guidance.prepareLaunch",
                "weaponUnit.shoot(index, Collections.singletonList(aimSource.aimContext()), gunner);",
                "gunner.setMissileCooldown(MISSILE_COOLDOWN_TICK);");
        assertContainsAll(clear,
                "gunner.setSeadMode(SEAD_NONE);",
                "gunner.setSeadTicks(0);",
                "gunner.setSeadRevengeTargetId(-1);",
                "gunner.setSeadImmediateFired(false);",
                "gunner.setSeadRevengeFired(false);",
                "gunner.setSeadCooldownTicks(SEAD_COOLDOWN_TICK);");
    }

    @Test
    void entityLifecycleKeepsServerAuthorityAndCleanupContract() throws IOException {
        String entity = read(ENTITY_SOURCE);
        String tick = section(entity,
                "public void tick()",
                "private void repairSeat(");
        String cleanup = section(entity,
                "public void clearDriverRideState()",
                "public String getProfileId()");
        String cooldowns = section(entity,
                "public void tickCooldowns()",
                "public boolean hasSmokeHoldTicks()");

        assertOrdered(tick,
                "super.tick();",
                "if (level().isClientSide())",
                "getVehicle() instanceof AbstractVehicle vehicle",
                "GunnerBrain.tick(this, vehicle);",
                "clearDriverRideState();",
                "setTrackedTarget(null);",
                "detachedTicks++;",
                "detachedTicks > 40",
                "discard();");
        assertContainsAll(cleanup,
                "refilledVehicleId = -1;",
                "recoveryTicks = 0;",
                "recoveryCooldownTicks = 0;",
                "tacticalHoldTicks = 0;",
                "tacticalEvadeTicks = 0;",
                "smokeHoldTicks = 0;",
                "homePosSet = false;",
                "seadMode = 0;",
                "seadRevengeTargetId = -1;",
                "seadCooldownTicks = 0;");
        assertContainsAll(cooldowns,
                "burstFireTicks--",
                "burstRestTicks--",
                "countermeasureCooldown--",
                "missileCooldown--",
                "ciwsTargetCooldowns.values().removeIf",
                "recoveryTicks--",
                "tacticalHoldTicks--",
                "tacticalEvadeTicks--",
                "smokeHoldTicks--",
                "seadCooldownTicks--");
    }

    @Test
    void phaseBActionGatewayOwnsAllMutableCapabilityBoundaries() throws IOException {
        String brain = read(BRAIN_SOURCE);
        String gateway = read(ACTION_GATEWAY_SOURCE);
        String weapon = read(WEAPON_ACTION_SOURCE);
        String movement = read(MOVEMENT_ACTION_SOURCE);
        String radar = read(RADAR_ACTION_SOURCE);
        String guidance = read(GUIDANCE_ACTION_SOURCE);
        String defense = read(DEFENSE_ACTION_SOURCE);
        String supply = read(SUPPLY_ACTION_SOURCE);

        assertContainsAll(gateway,
                "private final RVP_GunnerMovementActions movement",
                "private final RVP_GunnerWeaponActions weapons",
                "private final RVP_GunnerRadarActions radar",
                "private final RVP_GunnerGuidanceActions guidance",
                "private final RVP_GunnerDefenseActions defense",
                "private final RVP_GunnerSupplyActions supply");
        assertFalse(brain.contains("controlUnit."), "移动写入必须只存在于动作适配器");
        assertFalse(brain.contains(".shoot("), "本体发射必须只存在于武器动作适配器");
        assertFalse(brain.contains("setLockedEntity("), "雷达锁写入必须只存在于雷达动作边界");
        assertFalse(brain.contains("RVP_CountermeasureRuntimeManager.fire"), "干扰物写入必须只存在于防御动作边界");
        assertFalse(brain.contains("RVP_EcmActiveManager.tryFireForVehicle"), "ECM 写入必须只存在于防御动作边界");
        assertFalse(brain.contains("ObfuscationReflectionHelper"), "本体补给反射必须只存在于补给适配器");
        assertContainsAll(weapon, "weaponUnit.shoot(", "guidance.prepareLaunch");
        assertContainsAll(movement, "control.reset();", "control.forward = command.forward;");
        assertContainsAll(radar, "radar.setLockedEntity(", "root.setLockedEntity(");
        assertContainsAll(guidance,
                "GunnerGuidedWeaponController.tick(",
                "GunnerGuidedWeaponController.prepareForLaunch(");
        assertContainsAll(defense, "RVP_CountermeasureRuntimeManager.fire", "RVP_EcmActiveManager.tryFireForVehicle");
        assertContainsAll(supply, "ObfuscationReflectionHelper.findMethod", "rvpWeapon.ywzj_rvp$setReloadTime");
        assertFalse(weapon.contains("getPath()"), "武器动作层不得按武器 ID 路径分种类");
    }

    @Test
    void phaseAProfileSchemaAndDefaultsRemainFlatAndExplicit() throws IOException {
        String profile = read(PROFILE_SOURCE);

        assertContainsAll(profile,
                "@SerializedName(\"name\")",
                "private String name = \"default\";",
                "@SerializedName(\"faction\")",
                "private String faction = \"friendly\";",
                "@SerializedName(\"target_types\")",
                "@SerializedName(\"engagement_net_cooldown_tick\")",
                "private int engagementNetCooldownTick = 100;",
                "engagementNetCooldownTick = Math.max(0, Math.min(engagementNetCooldownTick, 1200));",
                "@SerializedName(\"gps_prefer_farthest\")",
                "private boolean gpsPreferFarthest = true;",
                "private double searchRadius = 96.0;",
                "private int scanIntervalTick = 10;",
                "private float fireWindowDeg = 6.0F;",
                "private double leadScale = 1.0;",
                "private int burstFireTick = 6;",
                "private int burstRestTick = 10;",
                "private double countermeasureRange = 36.0;",
                "private int countermeasureCooldownTick = 80;",
                "private boolean allowDrive = true;",
                "private double driveStopDistance = 12.0;",
                "private double rotaryCruiseAltitudeMin = 28.0;",
                "private double rotaryCruiseAltitudeMax = 60.0;",
                "private double fixedwingCruiseAltitudeMin = 150.0;",
                "private double fixedwingCruiseAltitudeMax = 500.0;");
        assertFalse(profile.contains("@SerializedName(\"behaviors\")"),
                "阶段 A 不得提前启用行为组合 schema");
    }

    @Test
    void engagementNetKeepsSlidingWindowAndHardLockSemantics() throws IOException {
        String net = read(ENGAGEMENT_NET_SOURCE);

        assertContainsAll(net,
                "public static final long HARD_LOCK_TICKS = 60L;",
                "public static long resolveWindowTick(",
                "minWindowTick * 2L",
                "vehicle.distanceTo(target) / 384.0D",
                "now + windowTick, now + HARD_LOCK_TICKS, shooter.getUUID()",
                "new Engagement(softUntil, existing.hardLockedUntil(), existing.engager())",
                "!engager.equals(gunner.getUUID())",
                "entry.getKey().dimension().equals(dimension)");
    }

    /** 以 UTF-8 读取待冻结的源码。 */
    private static String read(Path source) throws IOException {
        assertTrue(Files.isRegularFile(source), "缺少 Gunner 基线源码: " + source);
        return Files.readString(source);
    }

    /**
     * 取得两个稳定源码标记之间的文本，用于把断言限制在一个权威方法或职责段内。
     */
    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "缺少基线起始标记: " + startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "缺少基线结束标记: " + endMarker);
        return source.substring(start, end);
    }

    /** 断言指定语义片段全部存在。 */
    private static void assertContainsAll(String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(source.contains(fragment), "Gunner 行为基线缺少片段: " + fragment);
        }
    }

    /** 断言指定语义片段按现行权威顺序出现。 */
    private static void assertOrdered(String source, String... fragments) {
        int cursor = -1;
        for (String fragment : fragments) {
            int found = source.indexOf(fragment, cursor + 1);
            assertTrue(found >= 0, "Gunner 行为基线缺少或顺序改变: " + fragment);
            cursor = found;
        }
    }
}
