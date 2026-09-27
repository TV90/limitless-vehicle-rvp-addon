package org.ywzj.rvp.entity.gunner.ai.profile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import org.ywzj.rvp.entity.gunner.behavior.config.RVP_GunnerBehaviorPlan;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 已通过 schema v2 编译的 Gunner Profile。
 *
 * <p>本类不再由 Gson 直接反序列化。JSON 的严格字段校验、默认值和行为强类型配置均由
 * {@link RVP_GunnerProfileCompiler} 负责，因而不会误接收旧平铺字段。</p>
 */
public final class GunnerProfile {
    /** Profile 显示名称。 */
    private final String name;
    /** Profile 阵营。 */
    private final RVP_EnumGunnerFaction faction;
    /** 当前 Profile 编译得到的不可变行为计划。 */
    private final RVP_GunnerBehaviorPlan behaviorPlan;

    /* 以下字段只存在于编译期创建的行为专属参数视图中，不对应 Profile 顶层 JSON。 */
    /** 普通索敌允许的目标类别。 */ private List<String> targetTypes = new ArrayList<>(List.of("rvp:missile", "vehicle", "monster", "player"));
    /** 普通索敌是否优先把最远目标交给 GPS 武器。 */ private boolean gpsPreferFarthest = true;
    /** 普通索敌基础半径，单位格。 */ private double searchRadius = 96.0;
    /** 普通索敌扫描间隔，单位 tick。 */ private int scanIntervalTick = 10;
    /** 非制导武器允许的瞄准误差，单位度。 */ private float fireWindowDeg = 6.0F;
    /** 目标速度提前量倍率。 */ private double leadScale = 1.0;
    /** 单轮 burst 持续时间，单位 tick。 */ private int burstFireTick = 6;
    /** 两轮 burst 间歇，单位 tick。 */ private int burstRestTick = 10;
    /** 本体式反制武器搜索半径，单位格。 */ private double countermeasureRange = 36.0;
    /** 本体式反制武器释放冷却，单位 tick。 */ private int countermeasureCooldownTick = 80;
    /** 地面接敌与发射架停车距离，单位格。 */ private double driveStopDistance = 12.0;
    /** 卡住位移检查间隔，单位 tick。 */ private int driveStuckCheckTick = 20;
    /** 判定卡住的最大位移，单位格。 */ private double driveStuckDistance = 1.0;
    /** 倒车脱困持续时间，单位 tick。 */ private int driveRecoveryTick = 20;
    /** 旋翼巡航最低 AGL，单位格。 */ private double rotaryCruiseAltitudeMin = 28.0;
    /** 旋翼巡航最高 AGL，单位格。 */ private double rotaryCruiseAltitudeMax = 60.0;
    /** 固定翼巡航最低 AGL，单位格。 */ private double fixedwingCruiseAltitudeMin = 150.0;
    /** 固定翼巡航最高 AGL，单位格。 */ private double fixedwingCruiseAltitudeMax = 500.0;
    /** 固定翼最小作战半径，单位格。 */ private double fixedwingCombatRadiusMin = 40.0;
    /** 固定翼最大作战半径，单位格。 */ private double fixedwingCombatRadiusMax = 550.0;
    /** 地面巡逻大转弯最短间隔，单位 tick。 */ private int groundBigTurnIntervalTickMin = 300;
    /** 地面巡逻大转弯最长间隔，单位 tick。 */ private int groundBigTurnIntervalTickMax = 600;
    /** 地面巡逻大转弯最小角度，单位度。 */ private float groundBigTurnAngleDegMin = 120.0F;
    /** 地面巡逻大转弯最大角度，单位度。 */ private float groundBigTurnAngleDegMax = 180.0F;
    /** 地面巡逻一次大转弯持续时间，单位 tick。 */ private int groundBigTurnDurationTick = 40;
    /** 空战攻击阶段基础时长，单位 tick。 */ private int airAttackPhaseTick = 200;
    /** 空战脱离阶段基础时长，单位 tick。 */ private int airDisengagePhaseTick = 200;
    /** 初始脱离最短时长，单位 tick。 */ private int airInitialDisengageTickMin = 300;
    /** 初始脱离最长时长，单位 tick。 */ private int airInitialDisengageTickMax = 400;
    /** 组网目标降权窗口，单位 tick；0 表示关闭。 */ private int engagementNetCooldownTick = 100;
    /** CIWS 扫描间隔，单位 tick。 */ private int ciwsScanIntervalTick = 1;
    /** CIWS 自导弹对同一弹药目标的冷却，单位 tick。 */ private int ciwsTargetCooldownTick = 100;
    /** 地面接敌停车观察时长，单位 tick。 */ private int groundHoldTick = 100;
    /** 地面接敌侧移最短时长，单位 tick。 */ private int groundEvadeTickMin = 140;
    /** 地面接敌侧移最长时长，单位 tick。 */ private int groundEvadeTickMax = 280;
    /** 地面接敌侧移偏航角，单位度。 */ private float groundEvadeYawDeg = 55.0F;
    /** Smoke 威胁扫描间隔，单位 tick。 */ private int smokeScanIntervalTick = 10;
    /** Smoke 驶入/驻留时长，单位 tick。 */ private int smokeHoldTick = 260;
    /** Smoke 云搜索半径，单位格。 */ private double smokeLookRadius = 48.0;
    /** RVP 自动反制扫描间隔，单位 tick。 */ private int rvpCountermeasureScanIntervalTick = 5;
    /** RVP 自动反制同类释放冷却，单位 tick。 */ private int rvpCountermeasureCooldownTick = 100;
    /** RVP 自动反制导弹检测半径，单位格。 */ private double rvpMissileThreatRange = 256.0;
    /** RVP 自动反制雷达锁检测半径，单位格。 */ private double rvpRadarLockThreatRange = 1024.0;
    /** 主动 ECM 最低威胁检测半径，单位格。 */ private double ecmThreatRange = 200.0;
    /** 制导武器统一发射冷却，单位 tick。 */ private int guidedWeaponCooldownTick = 100;
    /** SEAD 雷达锁来源扫描间隔，单位 tick。 */ private int seadThreatScanIntervalTick = 10;
    /** SEAD 雷达锁来源扫描半径，单位格。 */ private double seadRadarLockRange = 1024.0;
    /** SEAD 飞离阶段时长，单位 tick。 */ private int seadFlyAwayTick = 100;
    /** SEAD 回旋阶段上限，单位 tick。 */ private int seadReversalTick = 160;
    /** SEAD 锁定发射阶段上限，单位 tick。 */ private int seadLockFireTick = 40;
    /** SEAD 单轮复仇总超时，单位 tick。 */ private int seadTimeoutTick = 400;
    /** SEAD 退出后的再次触发冷却，单位 tick。 */ private int seadCooldownTick = 400;

    public GunnerProfile(String name, RVP_EnumGunnerFaction faction, RVP_GunnerBehaviorPlan behaviorPlan) {
        this.name = name;
        this.faction = faction;
        this.behaviorPlan = behaviorPlan;
    }

    /** 为单个行为创建隔离的强类型参数视图。 */
    public GunnerProfile behaviorView() {
        return new GunnerProfile(name, faction, RVP_GunnerBehaviorPlan.empty());
    }

    public GunnerProfile targeting(List<String> types, boolean preferFarthest, double radius,
                                   int intervalTick, int engagementWindowTick) {
        targetTypes = new ArrayList<>(types);
        gpsPreferFarthest = preferFarthest;
        searchRadius = radius;
        scanIntervalTick = intervalTick;
        engagementNetCooldownTick = engagementWindowTick;
        return this;
    }

    public GunnerProfile weapon(float windowDeg, double lead, int burstFire, int burstRest,
                                int guidedCooldownTick, int ciwsCooldownTick) {
        fireWindowDeg = windowDeg;
        leadScale = lead;
        burstFireTick = burstFire;
        burstRestTick = burstRest;
        guidedWeaponCooldownTick = guidedCooldownTick;
        ciwsTargetCooldownTick = ciwsCooldownTick;
        return this;
    }

    public GunnerProfile ciws(int scanIntervalTick, int targetCooldownTick) {
        ciwsScanIntervalTick = scanIntervalTick;
        ciwsTargetCooldownTick = targetCooldownTick;
        return this;
    }

    public GunnerProfile weaponCountermeasure(double range, int cooldownTick) {
        countermeasureRange = range;
        countermeasureCooldownTick = cooldownTick;
        return this;
    }

    public GunnerProfile groundEngagement(double stopDistance, int holdTick, int evadeMin,
                                          int evadeMax, float evadeYawDeg) {
        driveStopDistance = stopDistance;
        groundHoldTick = holdTick;
        groundEvadeTickMin = evadeMin;
        groundEvadeTickMax = evadeMax;
        groundEvadeYawDeg = evadeYawDeg;
        return this;
    }

    public GunnerProfile smoke(int scanIntervalTick, int holdTick, double lookRadius) {
        smokeScanIntervalTick = scanIntervalTick;
        smokeHoldTick = holdTick;
        smokeLookRadius = lookRadius;
        return this;
    }

    public GunnerProfile rvpCountermeasure(int scanIntervalTick, int cooldownTick,
                                           double missileRange, double radarRange) {
        rvpCountermeasureScanIntervalTick = scanIntervalTick;
        rvpCountermeasureCooldownTick = cooldownTick;
        rvpMissileThreatRange = missileRange;
        rvpRadarLockThreatRange = radarRange;
        return this;
    }

    public GunnerProfile activeEcm(double threatRange) {
        ecmThreatRange = threatRange;
        return this;
    }

    public GunnerProfile sead(int scanIntervalTick, double radarRange, int flyAwayTick,
                              int reversalTick, int lockFireTick, int timeoutTick, int cooldownTick) {
        seadThreatScanIntervalTick = scanIntervalTick;
        seadRadarLockRange = radarRange;
        seadFlyAwayTick = flyAwayTick;
        seadReversalTick = reversalTick;
        seadLockFireTick = lockFireTick;
        seadTimeoutTick = timeoutTick;
        seadCooldownTick = cooldownTick;
        return this;
    }

    public GunnerProfile stuckRecovery(int checkTick, double distance, int recoveryTick) {
        driveStuckCheckTick = checkTick;
        driveStuckDistance = distance;
        driveRecoveryTick = recoveryTick;
        return this;
    }

    public GunnerProfile groundPatrol(int intervalMin, int intervalMax, float angleMin,
                                      float angleMax, int durationTick) {
        groundBigTurnIntervalTickMin = intervalMin;
        groundBigTurnIntervalTickMax = intervalMax;
        groundBigTurnAngleDegMin = angleMin;
        groundBigTurnAngleDegMax = angleMax;
        groundBigTurnDurationTick = durationTick;
        return this;
    }

    public GunnerProfile fixedWing(double altitudeMin, double altitudeMax, double radiusMin,
                                   double radiusMax, int attackTick, int disengageTick,
                                   int initialMin, int initialMax) {
        fixedwingCruiseAltitudeMin = altitudeMin;
        fixedwingCruiseAltitudeMax = altitudeMax;
        fixedwingCombatRadiusMin = radiusMin;
        fixedwingCombatRadiusMax = radiusMax;
        airAttackPhaseTick = attackTick;
        airDisengagePhaseTick = disengageTick;
        airInitialDisengageTickMin = initialMin;
        airInitialDisengageTickMax = initialMax;
        return this;
    }

    public GunnerProfile rotaryWing(double altitudeMin, double altitudeMax, int attackTick,
                                    int disengageTick, int initialMin, int initialMax) {
        rotaryCruiseAltitudeMin = altitudeMin;
        rotaryCruiseAltitudeMax = altitudeMax;
        airAttackPhaseTick = attackTick;
        airDisengagePhaseTick = disengageTick;
        airInitialDisengageTickMin = initialMin;
        airInitialDisengageTickMax = initialMax;
        return this;
    }

    public boolean matchesTarget(Entity entity) {
        for (String raw : targetTypes) {
            String type = raw.toLowerCase(Locale.ROOT);
            if ("vehicle".equals(type) && entity instanceof AbstractVehicle) return true;
            if ("player".equals(type) && entity instanceof Player) return true;
            if ("monster".equals(type)
                    && (entity instanceof Monster || entity.getType().getCategory() == MobCategory.MONSTER)) return true;
            if ("living".equals(type) && entity instanceof LivingEntity) return true;
        }
        return false;
    }

    public RVP_EnumGunnerFaction getFaction() { return faction; }
    public String getName() { return name; }
    public RVP_GunnerBehaviorPlan getBehaviorPlan() { return behaviorPlan; }
    /** 是否至少包含一个可提交载具移动的行为。 */
    public boolean isAllowDrive() {
        return behaviorPlan.behaviors().stream().anyMatch(org.ywzj.rvp.entity.gunner.behavior.api.RVP_IGunnerBehavior::controlsMovement);
    }
    public List<String> getTargetTypes() { return List.copyOf(targetTypes); }
    public int getEngagementNetCooldownTick() { return engagementNetCooldownTick; }
    public boolean isGpsPreferFarthest() { return gpsPreferFarthest; }
    public double getSearchRadius() { return searchRadius; }
    public int getScanIntervalTick() { return scanIntervalTick; }
    public float getFireWindowDeg() { return fireWindowDeg; }
    public double getLeadScale() { return leadScale; }
    public int getBurstFireTick() { return burstFireTick; }
    public int getBurstRestTick() { return burstRestTick; }
    public double getCountermeasureRange() { return countermeasureRange; }
    public int getCountermeasureCooldownTick() { return countermeasureCooldownTick; }
    public double getDriveStopDistance() { return driveStopDistance; }
    public int getDriveStuckCheckTick() { return driveStuckCheckTick; }
    public double getDriveStuckDistance() { return driveStuckDistance; }
    public int getDriveRecoveryTick() { return driveRecoveryTick; }
    public double getRotaryCruiseAltitudeMin() { return rotaryCruiseAltitudeMin; }
    public double getRotaryCruiseAltitudeMax() { return rotaryCruiseAltitudeMax; }
    public double getFixedwingCruiseAltitudeMin() { return fixedwingCruiseAltitudeMin; }
    public double getFixedwingCruiseAltitudeMax() { return fixedwingCruiseAltitudeMax; }
    public double getFixedwingCombatRadiusMin() { return fixedwingCombatRadiusMin; }
    public double getFixedwingCombatRadiusMax() { return fixedwingCombatRadiusMax; }
    public int getGroundBigTurnIntervalTickMin() { return groundBigTurnIntervalTickMin; }
    public int getGroundBigTurnIntervalTickMax() { return groundBigTurnIntervalTickMax; }
    public float getGroundBigTurnAngleDegMin() { return groundBigTurnAngleDegMin; }
    public float getGroundBigTurnAngleDegMax() { return groundBigTurnAngleDegMax; }
    public int getGroundBigTurnDurationTick() { return groundBigTurnDurationTick; }
    public int getAirAttackPhaseTick() { return airAttackPhaseTick; }
    public int getAirDisengagePhaseTick() { return airDisengagePhaseTick; }
    public int getAirInitialDisengageTickMin() { return airInitialDisengageTickMin; }
    public int getAirInitialDisengageTickMax() { return airInitialDisengageTickMax; }
    public int getCiwsScanIntervalTick() { return ciwsScanIntervalTick; }
    public int getCiwsTargetCooldownTick() { return ciwsTargetCooldownTick; }
    public int getGroundHoldTick() { return groundHoldTick; }
    public int getGroundEvadeTickMin() { return groundEvadeTickMin; }
    public int getGroundEvadeTickMax() { return groundEvadeTickMax; }
    public float getGroundEvadeYawDeg() { return groundEvadeYawDeg; }
    public int getSmokeScanIntervalTick() { return smokeScanIntervalTick; }
    public int getSmokeHoldTick() { return smokeHoldTick; }
    public double getSmokeLookRadius() { return smokeLookRadius; }
    public int getRvpCountermeasureScanIntervalTick() { return rvpCountermeasureScanIntervalTick; }
    public int getRvpCountermeasureCooldownTick() { return rvpCountermeasureCooldownTick; }
    public double getRvpMissileThreatRange() { return rvpMissileThreatRange; }
    public double getRvpRadarLockThreatRange() { return rvpRadarLockThreatRange; }
    public double getEcmThreatRange() { return ecmThreatRange; }
    public int getGuidedWeaponCooldownTick() { return guidedWeaponCooldownTick; }
    public int getSeadThreatScanIntervalTick() { return seadThreatScanIntervalTick; }
    public double getSeadRadarLockRange() { return seadRadarLockRange; }
    public int getSeadFlyAwayTick() { return seadFlyAwayTick; }
    public int getSeadReversalTick() { return seadReversalTick; }
    public int getSeadLockFireTick() { return seadLockFireTick; }
    public int getSeadTimeoutTick() { return seadTimeoutTick; }
    public int getSeadCooldownTick() { return seadCooldownTick; }
}
