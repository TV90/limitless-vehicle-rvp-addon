package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.entity.projectile.RVP_RocketEntity;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.radar.RVP_AspectRcs;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.vehicle.entity.weapon.AerialBombEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.entity.weapon.RocketEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GunnerTargeting {

    private GunnerTargeting() {}

    /** 飞机索敌半径倍率：大幅扩展 gunner 空对地（及空对空）索敌范围，供远程制导武器打击远处目标。 */
    private static final double AIR_SEARCH_MULTIPLIER = 6.0;

    @Nullable
    public static Entity findBestTarget(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit, GunnerProfile profile) {
        double radius = resolveSearchRadius(vehicle, weaponUnit, profile);
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        boolean launcher = GunnerBrain.hasLauncherDeployConfig(vehicle);
        // O(实体) 遍历已加载实体，替代 ±radius（带雷达时可达数千格）立方体 getEntities（服务端掉 TPS）
        List<Entity> entities = collectTargetEntities(vehicle, radius,
                entity -> passesTargetCollectionGates(gunner, vehicle, weaponUnit,
                        vehicleTeam, gunnerTeam, entity, profile, radius));
        // 目的：限位窗口（硬禁）——被其它同 faction gunner 交战后 60t 内本 gunner 完全不可选
        //（即使它是唯一候选；交战者本人不受限），排除后再做未交战/排斥两池
        List<Entity> rvpAmmo = entities.stream()
                .filter(entity -> isInterceptableRvpProjectile(entity)
                        && !RVP_GunnerEngagementNet.isHardLockedFor(
                                vehicle.level(), gunner.getProfileFaction(), entity, gunner))
                .toList();
        if (!rvpAmmo.isEmpty()) {
            // 目的：组网智能拦截（2026-09-15）——同 faction 网络内已被任一 gunner 射击过的
            // 导弹（窗口 = profile engagement_net_cooldown_tick）沉入第二池，优先在"未交战"
            // 池中选最优；全部候选均已交战时忽略降权照常选择（降权非禁选，仍有弹的继续打）。
            // 多台同 faction 防空车面对一波来袭时弹幕自动分配到不同导弹上。
            if (profile.getEngagementNetCooldownTick() > 0) {
                // 窗口（随交战距离滑动，见 GunnerBrain.computeEngagementNetWindow）在记账时
                // 已烘进侧表截止 tick，查询端只需判断是否仍在窗口内
                List<Entity> fresh = rvpAmmo.stream()
                        .filter(entity -> !RVP_GunnerEngagementNet.isRecentlyEngaged(
                                vehicle.level(), gunner.getProfileFaction(), entity))
                        .toList();
                if (!fresh.isEmpty()) {
                    rvpAmmo = fresh;
                }
            }
            return rvpAmmo.stream()
                    .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity, launcher)))
                    .orElse(null);
        }
        // 优先级 1.5：敌方被动电子战假目标（仅次于导弹，用户批示"会，且优先级高"）
        List<Entity> ewDecoys = entities.stream()
                .filter(entity -> entity instanceof org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity)
                .toList();
        if (!ewDecoys.isEmpty()) {
            return pickBestInTier(gunner, vehicle, weaponUnit, profile, ewDecoys, launcher);
        }
        List<Entity> hostileGunnerVehicles = entities.stream()
                .filter(entity -> isRelativeHostileGunnerVehicle(gunner, entity))
                .toList();
        if (!hostileGunnerVehicles.isEmpty()) {
            // 优先级2：敌方 gunner 载具（高于玩家/其它）
            return pickBestInTier(gunner, vehicle, weaponUnit, profile, hostileGunnerVehicles, launcher);
        }
        List<Entity> playerTargets = entities.stream()
                .filter(GunnerTargeting::isPlayerTarget)
                .toList();
        if (!playerTargets.isEmpty()) {
            // 优先级3：玩家（或玩家驾驶的载具）
            return pickBestInTier(gunner, vehicle, weaponUnit, profile, playerTargets, launcher);
        }
        // 优先级4：其它有效目标（排除处于限位硬禁期的可拦截弹——防止单弹场景从兜底层漏选）
        List<Entity> fallbackTargets = entities.stream()
                .filter(entity -> !(isInterceptableRvpProjectile(entity)
                        && RVP_GunnerEngagementNet.isHardLockedFor(
                                vehicle.level(), gunner.getProfileFaction(), entity, gunner)))
                .toList();
        return pickBestInTier(gunner, vehicle, weaponUnit, profile, fallbackTargets, launcher);
    }

    /**
     * 在某一优先级层级内选最优目标：优先选最远的 GPS 可打击目标（gps_prefer_farthest 启用时），
     * 否则按评分（距离 + 夹角×32，近的、正对机头的优先）。
     */
    @Nullable
    private static Entity pickBestInTier(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                         GunnerProfile profile, List<Entity> tier, boolean launcher) {
        if (tier.isEmpty()) {
            return null;
        }
        if (profile.isGpsPreferFarthest()) {
            List<Entity> gpsTargets = tier.stream()
                    .filter(entity -> GunnerWeaponSuitability.hasUsableGpsWeaponForTarget(weaponUnit, entity))
                    .toList();
            if (!gpsTargets.isEmpty()) {
                return gpsTargets.stream()
                        .max(Comparator.comparingDouble(entity -> vehicle.position().distanceToSqr(entity.position())))
                        .orElse(null);
            }
        }
        return tier.stream()
                .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity, launcher)))
                .orElse(null);
    }

    /**
     * [RVP] gunner 索敌的分角度 RCS 感知（2026-09-16）：载具目标的有效感知距离 =
     * 索敌半径 × 综合隐身因子（{@code rvp_radar_rcs_factor} 分角度插值 × 开启弹舱增幅）——
     * 正面隐身的载具要逼近到很近才会被 gunner 索敌发现；非载具目标（导弹/步行玩家等）不受影响。
     * 仅作用于索敌收集：已锁定目标的跟踪保持不查此项（探测难、跟踪易）。
     */
    private static boolean passesAspectPerception(AbstractVehicle observer, Entity entity, double radius) {
        // 目的：乘员随载具隐身（2026-09-16 修复"gunner 没找到飞机却感知到驾驶员"）——
        // collectTargetEntities 不排除骑乘者，驾驶隐身战机时玩家的 Player 实体会作为
        // 独立候选进入索敌；原实现对非载具实体直接放行、不吃分角度 RCS，导致 AI 在
        // 全索敌半径（有中继时 3500 格）"感知到驾驶员"而载具隐身失效（AI 驾驶员是
        // GunnerEntity 非 Player，反向无此洞，造成"AI 看得见我、我看不见 AI"的不对称）。
        // 修复：候选骑乘在载具上时，RCS 因子按所乘载具的 combinedFactor 判定；
        // 真正的步行玩家/弹药维持原"直接放行"。
        AbstractVehicle effectiveVehicle = entity instanceof AbstractVehicle targetVehicle
                ? targetVehicle
                : (entity.getVehicle() instanceof AbstractVehicle ridden ? ridden : null);
        if (effectiveVehicle == null) {
            return true;
        }
        double factor = RVP_AspectRcs.combinedFactor(effectiveVehicle, observer.position());
        double effective = radius * factor;
        double distSqr = observer.position().distanceToSqr(entity.position());
        if (distSqr > effective * effective) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                RVP_GunnerLockDebug.logPerceptionReject(observer, effectiveVehicle,
                        factor, effective, Math.sqrt(distSqr));
            }
            return false;
        }
        return true;
    }

    /** 玩家目标：玩家本体，或由玩家驾驶的载具（已通过 isValidTarget 的敌我过滤，此处只需分类）。 */
    private static boolean isPlayerTarget(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        return entity instanceof AbstractVehicle vehicle && vehicle.getDriver() instanceof Player;
    }

    /** 基础搜索半径（不含雷达扩展）。 */
    private static double getTargetSearchRadius(AbstractVehicle vehicle, GunnerProfile profile) {
        double base = profile.getSearchRadius();
        if (vehicle instanceof org.ywzj.vehicle.entity.vehicle.FixedWingVehicle
                || vehicle instanceof org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle) {
            // 大幅扩展飞机索敌范围：让 gunner 能发现很远的地面目标（供远程制导武器打击）。
            // 注：collectTargetEntities 本就 O(实体) 遍历，半径增大不增加遍历次数，仅扩大命中范围，
            // 实际能否开火仍由武器 lock_target_distance_range 等门控决定。
            return base * AIR_SEARCH_MULTIPLIER;
        }
        return base;
    }

    /**
     * 索敌半径：基础为 profile 搜索半径；若载具带雷达（自身或外置中继），
     * 扩展为雷达最大扫描距离——gunner 对空攻击范围覆盖雷达扫描到的所有目标。
     */
    private static double resolveSearchRadius(AbstractVehicle vehicle, @Nullable WeaponUnit weaponUnit, GunnerProfile profile) {
        double base = getTargetSearchRadius(vehicle, profile);
        double radarRange = resolveRadarScanRange(vehicle, weaponUnit);
        return Math.max(base, radarRange);
    }

    /** 有雷达（自身或外置中继）时返回最大扫描距离，否则 0。 */
    private static double resolveRadarScanRange(AbstractVehicle vehicle, @Nullable WeaponUnit weaponUnit) {
        double best = 0;
        if (weaponUnit != null) {
            for (RadarUnit radar : weaponUnit.getRadarUnits()) {
                best = Math.max(best, radar.getMaxScanDistance());
            }
        }
        AbstractVehicle relay = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(vehicle).orElse(null);
        if (relay != null) {
            RadarUnit relayRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relay);
            if (relayRadar != null) {
                best = Math.max(best, relayRadar.getMaxScanDistance());
            }
        }
        return best;
    }

    @Nullable
    public static AmmoEntity findAmmoThreat(GunnerEntity gunner, AbstractVehicle vehicle, double radius) {
        return findAmmoThreat(gunner, vehicle, radius, 32.0, 25.0);
    }

    /**
     * 查找来袭弹药威胁（箔条/主动ECM 共用）。
     *
     * @param scanRadius       扫描半径（收集此范围内的弹药）
     * @param closeRangeCap    视为"威胁"的最大距离（超出则不触发）；主动ECM 传其干扰半径以提前触发
     * @param maxTimeToImpact  最大预计命中 tick（超出视为尚远、不紧急）；主动ECM 传大值以放宽
     */
    public static AmmoEntity findAmmoThreat(GunnerEntity gunner, AbstractVehicle vehicle, double scanRadius,
                                            double closeRangeCap, double maxTimeToImpact) {
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        // O(实体) 遍历已加载实体，替代 ±radius 立方体 getEntities
        List<Entity> entities = collectTargetEntities(vehicle, scanRadius, entity -> entity instanceof AmmoEntity ammo
                && ammo.isAlive()
                && ammo.vehicle != vehicle
                && !isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam, ammo.getOwner()));
        Vec3 vehiclePos = vehicle.position();
        AmmoEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Entity entity : entities) {
            AmmoEntity ammo = (AmmoEntity) entity;
            if (!isDangerousAmmo(ammo)) {
                continue;
            }
            if (ammo.vehicle == null) {
                continue;
            }
            Vec3 ammoVel = ammo.getDeltaMovement();
            if (ammoVel.lengthSqr() < 0.04) {
                continue;
            }
            Vec3 toVehicle = vehiclePos.subtract(ammo.position());
            double distSqr = toVehicle.lengthSqr();
            if (distSqr <= 1.0 || distSqr >= bestDistSqr) {
                continue;
            }
            double dist = Math.sqrt(distSqr);
            if (dist > Math.min(scanRadius, closeRangeCap)) {
                continue;
            }
            Vec3 toVehicleDir = toVehicle.normalize();
            double closing = ammoVel.dot(toVehicleDir);
            if (closing <= 0.35) {
                continue;
            }
            double timeToImpactTick = dist / closing * 20.0;
            if (timeToImpactTick > maxTimeToImpact) {
                continue;
            }
            best = ammo;
            bestDistSqr = distSqr;
        }
        return best;
    }

    public static boolean isDangerousAmmo(AmmoEntity ammo) {
        if (ammo instanceof org.ywzj.rvp.entity.projectile.RVP_BaseBullet) {
            return true;
        }
        if (ammo instanceof MissileEntity) {
            return true;
        }
        if (ammo instanceof AerialBombEntity) {
            return true;
        }
        if (ammo instanceof RocketEntity) {
            return true;
        }
        if (ammo.getWeaponId() == null) {
            return false;
        }
        String path = ammo.getWeaponId().getPath().toLowerCase(Locale.ROOT);
        return path.contains("missile") || path.contains("bomb");
    }

    public static Vec3 predictAimPoint(Vec3 sourcePos, Entity target) {
        Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
        double leadScale = Mth.clamp(sourcePos.distanceToSqr(center) / 256.0, 0.5, 4.0);
        return center.add(target.getDeltaMovement().scale(leadScale));
    }

    /**
     * 索敌收集谓词：与原 {@code isValidTarget && 武器可用 && RCS 感知} 短路顺序完全一致，
     * 仅在候选为载具/玩家且被拒时把拒绝首因写入 gunnerlock 诊断
     * （2026-09-16"只锁定不攻击"排查引入，专用服务器为零开销——dist 守卫后不执行）。
     */
    private static boolean passesTargetCollectionGates(GunnerEntity gunner, AbstractVehicle vehicle,
                                                       WeaponUnit weaponUnit, @Nullable Team vehicleTeam,
                                                       @Nullable Team gunnerTeam, Entity entity,
                                                       GunnerProfile profile, double radius) {
        if (!isValidTarget(gunner, vehicle, vehicleTeam, gunnerTeam, entity, profile)) {
            if (FMLEnvironment.dist == Dist.CLIENT
                    && (entity instanceof AbstractVehicle || entity instanceof Player)) {
                RVP_GunnerLockDebug.logCandidateReject(vehicle, entity,
                        classifyTargetReject(gunner, vehicle, vehicleTeam, gunnerTeam, entity, profile));
            }
            return false;
        }
        if (!GunnerWeaponSuitability.hasUsableWeaponForTarget(weaponUnit, entity)) {
            if (FMLEnvironment.dist == Dist.CLIENT && entity instanceof AbstractVehicle targetVehicle) {
                double agl = targetVehicle.getY() - targetVehicle.level().getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        Mth.floor(targetVehicle.getX()), Mth.floor(targetVehicle.getZ()));
                RVP_GunnerLockDebug.logCandidateReject(vehicle, entity,
                        "WEAPON_UNUSABLE agl=" + String.format("%.0f", agl));
            }
            return false;
        }
        return passesAspectPerception(vehicle, entity, radius);
    }

    /** gunnerlock 诊断用：按 {@link #isValidTarget} 的判定顺序给出拒绝首因（仅分类，无副作用）。 */
    private static String classifyTargetReject(GunnerEntity gunner, AbstractVehicle vehicle,
                                               @Nullable Team vehicleTeam, @Nullable Team gunnerTeam,
                                               Entity entity, GunnerProfile profile) {
        if (!entity.isAlive() || entity == gunner || entity == vehicle) {
            return "DEAD_OR_SELF";
        }
        if (vehicle.getPassengers().contains(entity)) {
            return "OWN_PASSENGER";
        }
        if (entity instanceof Player player && isProtectedCreativePlayer(vehicle, player)) {
            return "CREATIVE_PLAYER";
        }
        if (entity instanceof AbstractVehicle targetVehicle && hasProtectedCreativePassenger(vehicle, targetVehicle)) {
            return "CREATIVE_PASSENGER";
        }
        TargetMatch match = matchProfileTarget(gunner, vehicle, entity, profile);
        if (!match.allowed) {
            return "PROFILE_TYPE";
        }
        if (gunner.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY && gunner.isOwnedBy(entity)) {
            return "OWNED";
        }
        if (shouldApplyTeamFilter(entity, profile.getFaction()) && !match.bypassTeamFilter
                && (isAllied(entity, vehicleTeam) || isAllied(entity, gunnerTeam))) {
            return "ALLIED";
        }
        return "OTHER";
    }

    private static boolean isValidTarget(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Team vehicleTeam, @Nullable Team gunnerTeam, Entity entity, GunnerProfile profile) {
        if (!entity.isAlive() || entity == gunner || entity == vehicle) {
            return false;
        }
        if (vehicle.getPassengers().contains(entity)) {
            return false;
        }
        if (entity instanceof AmmoEntity ammo) {
            if (ammo.vehicle == vehicle) {
                return false;
            }
            if (isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam, ammo.getOwner())) {
                return false;
            }
        }
        if (entity instanceof Player player) {
            if (isProtectedCreativePlayer(vehicle, player)) {
                return false;
            }
        }
        if (entity instanceof AbstractVehicle targetVehicle && hasProtectedCreativePassenger(vehicle, targetVehicle)) {
            return false;
        }
        // 被动电子战假目标：仅敌对方可攻击（归属方/友方不可见、不可锁、不可打，§7.3/§7.4）；
        // 且已"烧穿"该干扰的 gunner 载具不再攻击幻影（直接看穿、锁定真实目标）
        if (entity instanceof org.ywzj.rvp.entity.ecm.RVP_EcmDecoyEntity decoy) {
            return org.ywzj.rvp.ecm.RVP_EcmIff.isDecoyHostileTo(decoy, vehicle)
                    && !org.ywzj.rvp.ecm.RVP_EcmPassiveManager.isViewerBurnedThrough(decoy, vehicle);
        }
        TargetMatch match = matchProfileTarget(gunner, vehicle, entity, profile);
        if (!match.allowed) {
            return false;
        }
        // ENEMY faction 无差别攻击所有人，包括放置者自己
        if (gunner.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY && gunner.isOwnedBy(entity)) {
            return false;
        }
        if (shouldApplyTeamFilter(entity, profile.getFaction())
                && !match.bypassTeamFilter
                && (isAllied(entity, vehicleTeam) || isAllied(entity, gunnerTeam))) {
            return false;
        }
        return true;
    }

    /**
     * 步行玩家保护（2026-09-16 方案A 最终定版）：本方法只服务 isValidTarget 的**步行玩家**
     * 实体分支——创造/旁观步兵**任何难度都不被攻击**，生存/冒险步行可被攻击。
     * 难度判定在载具分支：见 {@link #hasProtectedCreativePassenger}（载具内创造乘员仅
     * 非困难难度受保护，困难难度可被打）。难度不参与本方法判定。
     */
    private static boolean isProtectedCreativePlayer(AbstractVehicle sourceVehicle, Player player) {
        if (player.isSpectator()) {
            return true;
        }
        return player.isCreative();
    }

    /**
     * 载具乘员保护（2026-09-16 方案A 最终定版）：车上存在创造/旁观乘员（含驾驶员）时载具
     * 受保护，但**仅非困难难度**——困难难度下创造驾驶员/乘员的载具可被正常攻击；
     * 生存乘员不提供任何保护。难度不影响生存目标。
     * （对照：创造**步行**玩家走 {@link #isProtectedCreativePlayer}，任何难度都保护。）
     */
    private static boolean hasProtectedCreativePassenger(AbstractVehicle sourceVehicle, AbstractVehicle targetVehicle) {
        boolean hasProtectedCreativeOccupant = false;
        for (Entity passenger : targetVehicle.getPassengers()) {
            if (passenger instanceof Player player && (player.isSpectator() || player.isCreative())) {
                hasProtectedCreativeOccupant = true;
                break;
            }
        }
        return hasProtectedCreativeOccupant && sourceVehicle.level().getDifficulty() != Difficulty.HARD;
    }

    private static boolean shouldApplyTeamFilter(Entity entity, RVP_EnumGunnerFaction faction) {
        if (faction == RVP_EnumGunnerFaction.ENEMY) {
            return false;
        }
        return true;
    }

    private static boolean isAllied(@Nullable Entity entity, @Nullable Team alliedTeam) {
        if (entity == null) {
            return false;
        }
        Team targetTeam = entity.getTeam();
        return alliedTeam != null && targetTeam != null && targetTeam.isAlliedTo(alliedTeam);
    }

    /**
     * 统一判断弹药owner是否对当前gunner友方（不拦截）
     * - owner == gunner：自己发射的弹药
     * - owner是GunnerEntity：同faction视为友方（敌对gunner之间无team但同faction应互视为友方）
     * - owner是Player或其他：走原有的载具乘客/放置者/team联盟判断
     * 包内共享：CIWS 拦截判定与 GunnerBrain 烟雾规避的敌方导弹识别共用此语义。
     */
    public static boolean isFriendlyAmmoOwner(GunnerEntity gunner, AbstractVehicle vehicle,
                                               @Nullable Team vehicleTeam, @Nullable Team gunnerTeam,
                                               @Nullable Entity owner) {
        if (owner == null) {
            return false;
        }
        if (owner == gunner) {
            return true;
        }
        if (owner instanceof GunnerEntity ownerGunner) {
            return ownerGunner.getProfileFaction() == gunner.getProfileFaction();
        }
        if (vehicle.getPassengers().contains(owner)) {
            return true;
        }
        // ENEMY faction 无差别攻击所有人，包括放置者自己
        if (gunner.getProfileFaction() != RVP_EnumGunnerFaction.ENEMY && gunner.isOwnedBy(owner)) {
            return true;
        }
        return isAllied(owner, vehicleTeam) || isAllied(owner, gunnerTeam);
    }

    private static TargetMatch matchProfileTarget(GunnerEntity gunner, AbstractVehicle vehicle, Entity entity, GunnerProfile profile) {
        List<String> targetTypes = profile.getTargetTypes();
        if (targetTypes == null || targetTypes.isEmpty()) {
            return TargetMatch.DISALLOWED;
        }

        for (String raw : targetTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String type = raw.toLowerCase(Locale.ROOT).trim();
            if ("rvp:missile".equals(type) && isInterceptableRvpProjectile(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("monster".equals(type) && isMonster(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("player".equals(type) && entity instanceof Player) {
                return TargetMatch.allowed(false);
            }
            if ("neutral".equals(type) && isNeutralLiving(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("living".equals(type) && entity instanceof LivingEntity) {
                return TargetMatch.allowed(false);
            }
            if ("vehicle".equals(type) && entity instanceof AbstractVehicle) {
                return isOccupiedVehicle(entity) ? TargetMatch.allowed(false) : TargetMatch.DISALLOWED;
            }
            if ("vehicle:enemy_gunner".equals(type) && isVehicleDrivenByFaction(entity, RVP_EnumGunnerFaction.ENEMY)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:friendly_gunner".equals(type) && isVehicleDrivenByFaction(entity, RVP_EnumGunnerFaction.FRIENDLY)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:team_gunner".equals(type) && isVehicleDrivenByFaction(entity, RVP_EnumGunnerFaction.TEAM)) {
                return TargetMatch.allowed(true);
            }
            if ("vehicle:player".equals(type) && isVehicleDrivenByPlayer(entity)) {
                return TargetMatch.allowed(false);
            }
            if ("vehicle:non_allied_gunner".equals(type) && isVehicleDrivenByNonAlliedGunner(entity, gunner.getTeam())) {
                return TargetMatch.allowed(false);
            }
        }
        return TargetMatch.DISALLOWED;
    }

    private static boolean isRvpMissile(Entity entity) {
        return entity instanceof RVP_MissileEntity || entity instanceof MissileEntity;
    }

    private static boolean isRvpBomb(Entity entity) {
        return entity instanceof AerialBombEntity || entity instanceof RVP_BombEntity;
    }

    private static boolean isRvpRocket(Entity entity) {
        return entity instanceof RVP_RocketEntity || entity instanceof RocketEntity;
    }

    private static boolean isInterceptableRvpProjectile(Entity entity) {
        return isRvpMissile(entity) || isRvpBomb(entity) || isRvpRocket(entity);
    }

    /**
     * 搜索普通搜索范围内的RVP弹药（导弹/炸弹），无高度限制，每tick调用
     */
    @Nullable
    public static AmmoEntity findNearbyAmmoTarget(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit, GunnerProfile profile) {
        double radius = getTargetSearchRadius(vehicle, profile);
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        List<Entity> entities = collectTargetEntities(vehicle, radius, entity ->
                isValidTarget(gunner, vehicle, vehicleTeam, gunnerTeam, entity, profile)
                        && (isRvpMissile(entity) || isRvpBomb(entity) || isRvpRocket(entity))
                        && GunnerWeaponSuitability.hasUsableWeaponForTarget(weaponUnit, entity));
        if (entities.isEmpty()) {
            return null;
        }
        return (AmmoEntity) entities.stream()
                .min(Comparator.comparingDouble(entity -> score(vehicle, weaponUnit, entity)))
                .orElse(null);
    }

    /**
     * CIWS target search: find the closest non-allied RVP missile or bomb
     * within 1000m that is above 50m AGL.
     */
    @Nullable
    public static AmmoEntity findCiwsTarget(GunnerEntity gunner, AbstractVehicle vehicle) {
        final double ciwsRange = 1000.0;
        final double minAgl = GunnerBrain.hasLauncherDeployConfig(vehicle) ? 0.0 : 50.0;
        Team vehicleTeam = vehicle.getTeam();
        Team gunnerTeam = gunner.getTeam();
        // O(实体) 遍历已加载实体，替代 ±1000 立方体 getEntities（2000³，服务端掉 TPS）
        List<Entity> candidates = collectTargetEntities(vehicle, ciwsRange, entity -> {
            if (!(entity instanceof AmmoEntity ammo)) {
                return false;
            }
            if (!ammo.isAlive()) {
                return false;
            }
            if (ammo.vehicle == vehicle) {
                return false;
            }
            if (isFriendlyAmmoOwner(gunner, vehicle, vehicleTeam, gunnerTeam, ammo.getOwner())) {
                return false;
            }
            if (!isInterceptableRvpProjectile(entity)) {
                return false;
            }
            if (gunner.isCiwsTargetOnCooldown(entity)) {
                return false;
            }
            double ammoAgl = entity.getY() - entity.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    net.minecraft.util.Mth.floor(entity.getX()),
                    net.minecraft.util.Mth.floor(entity.getZ()));
            return ammoAgl >= minAgl;
        });
        // 目的：限位窗口（硬禁）——被其它同 faction gunner 交战后 60t 内本 gunner 完全不可选
        //（即使它是唯一候选；交战者本人不受限）
        candidates.removeIf(entity -> RVP_GunnerEngagementNet.isHardLockedFor(
                vehicle.level(), gunner.getProfileFaction(), entity, gunner));
        if (candidates.isEmpty()) {
            return null;
        }
        // 目的：组网智能拦截的真正读取点（2026-09-15 修复）——炮车选来袭导弹走的根本不是
        // findBestTarget 的导弹层，而是本 CIWS 层（tickTargeting 每 tick 优先走这里）；此前
        // 组网表只写在 findBestTarget 侧导致组网形同虚设。两池逻辑：优先在"未被同 faction
        // 网络交战"的候选里选最近；全部已交战则回退全候选照常选（降权非禁选——单一来袭时
        // fresh 池必然为空，回退后照常拦截）。窗口在记账时已烘进截止 tick，此处只判过期。
        List<Entity> freshCandidates = candidates.stream()
                .filter(entity -> !RVP_GunnerEngagementNet.isRecentlyEngaged(
                        vehicle.level(), gunner.getProfileFaction(), entity))
                .toList();
        List<Entity> pool = freshCandidates.isEmpty() ? candidates : freshCandidates;
        Vec3 gunnerPos = gunner.position();
        Entity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (Entity entity : pool) {
            double distSqr = gunnerPos.distanceToSqr(entity.position());
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = entity;
            }
        }
        return (AmmoEntity) best;
    }

    /** O(实体) 索敌：遍历已加载实体并保留原 getEntities(±radius 立方体) 的 bbox 交集语义，
     *  但避免超大立方体 section 索引遍历（服务端掉 TPS）。 */
    public static List<Entity> collectTargetEntities(AbstractVehicle vehicle, double radius, java.util.function.Predicate<Entity> filter) {
        List<Entity> result = new java.util.ArrayList<>();
        if (radius <= 0 || !(vehicle.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return result;
        }
        AABB box = vehicle.getBoundingBox().inflate(radius);
        for (Entity entity : serverLevel.getEntities().getAll()) {
            if (entity == vehicle || !entity.isAlive() || !entity.getBoundingBox().intersects(box)) {
                continue;
            }
            if (filter.test(entity)) {
                result.add(entity);
            }
        }
        return result;
    }

    private static final class TargetMatch {
        private static final TargetMatch DISALLOWED = new TargetMatch(false, false);
        private final boolean allowed;
        private final boolean bypassTeamFilter;

        private TargetMatch(boolean allowed, boolean bypassTeamFilter) {
            this.allowed = allowed;
            this.bypassTeamFilter = bypassTeamFilter;
        }

        private static TargetMatch allowed(boolean bypassTeamFilter) {
            return new TargetMatch(true, bypassTeamFilter);
        }
    }

    private static boolean isMonster(Entity entity) {
        return entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
                || entity instanceof net.minecraft.world.entity.monster.Monster;
    }

    private static boolean isNeutralLiving(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (entity instanceof Player) {
            return false;
        }
        if (entity instanceof AbstractVehicle) {
            return false;
        }
        if (entity instanceof GunnerEntity) {
            return false;
        }
        return !isMonster(entity);
    }

    private static boolean isVehicleDrivenByFaction(Entity entity, RVP_EnumGunnerFaction faction) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        Entity driver = vehicle.getDriver();
        if (!(driver instanceof GunnerEntity gunnerDriver)) {
            return false;
        }
        GunnerProfile driverProfile = GunnerProfileManager.INSTANCE.getProfile(
                GunnerProfileManager.INSTANCE.normalizeProfileId(gunnerDriver.getProfileId())
        );
        return driverProfile.getFaction() == faction;
    }

    private static boolean isRelativeHostileGunnerVehicle(GunnerEntity sourceGunner, Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        Entity driver = vehicle.getDriver();
        if (!(driver instanceof GunnerEntity targetGunner)) {
            return false;
        }

        RVP_EnumGunnerFaction sourceFaction = sourceGunner.getProfileFaction();
        RVP_EnumGunnerFaction targetFaction = targetGunner.getProfileFaction();

        if (sourceFaction == RVP_EnumGunnerFaction.ENEMY) {
            return targetFaction == RVP_EnumGunnerFaction.FRIENDLY || targetFaction == RVP_EnumGunnerFaction.TEAM;
        }
        if (sourceFaction == RVP_EnumGunnerFaction.FRIENDLY) {
            return targetFaction == RVP_EnumGunnerFaction.ENEMY;
        }
        if (sourceFaction == RVP_EnumGunnerFaction.TEAM) {
            if (targetFaction == RVP_EnumGunnerFaction.ENEMY) {
                return true;
            }
            if (targetFaction == RVP_EnumGunnerFaction.TEAM) {
                Team sourceTeam = sourceGunner.getTeam();
                Team targetTeam = targetGunner.getTeam();
                return sourceTeam == null || targetTeam == null || !targetTeam.isAlliedTo(sourceTeam);
            }
        }
        return false;
    }

    private static boolean isVehicleDrivenByNonAlliedGunner(Entity entity, @Nullable Team alliedTeam) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        Entity driver = vehicle.getDriver();
        if (!(driver instanceof GunnerEntity gunnerDriver)) {
            return false;
        }
        Team driverTeam = gunnerDriver.getTeam();
        return alliedTeam == null || driverTeam == null || !driverTeam.isAlliedTo(alliedTeam);
    }

    private static boolean isVehicleDrivenByPlayer(Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        return vehicle.getDriver() instanceof Player;
    }

    private static boolean isOccupiedVehicle(Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        return vehicle.getDriver() != null;
    }

    private static double score(AbstractVehicle vehicle, WeaponUnit weaponUnit, Entity entity) {
        return score(vehicle, weaponUnit, entity, false);
    }

    private static double score(AbstractVehicle vehicle, WeaponUnit weaponUnit, Entity entity, boolean noAnglePenalty) {
        Vec3 toTarget = entity.position().add(0, entity.getBbHeight() * 0.5, 0)
                .subtract(weaponUnit.worldPivotPosition());
        double distance = toTarget.length();
        if (noAnglePenalty) {
            return distance;
        }
        Vec3 forward = weaponUnit.worldVec();
        if (forward.lengthSqr() < 1.0E-4) {
            forward = VectorUtil.rotToVec(vehicle.getXRot(), vehicle.getYRot());
        }
        double angle = VectorUtil.angleBetween(forward.normalize(), toTarget.normalize());
        return distance + angle * 32.0;
    }
}
