package org.ywzj.rvp.entity.gunner.ai;

import com.mojang.logging.LogUtils;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionGateway;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerMovementActions;
import org.ywzj.rvp.vehicle.BoneEcmActiveConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle.Seat;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import java.util.List;

public final class GunnerBrain {

    private GunnerBrain() {}
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Gunner 对本体与 RVP 写操作的唯一动作网关。 */
    private static final RVP_GunnerActionGateway ACTIONS = RVP_GunnerActionGateway.INSTANCE;
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
            // 调用补给动作适配器，集中处理首次接管、能源和武器补给。
            ACTIONS.supply().refillOnDriverEnter(gunner, vehicle);
            ACTIONS.supply().sustainDriverAmmo(gunner, vehicle);
        } else {
            // 调用补给动作适配器，清理失去司机资格后的无限弹药计时。
            ACTIONS.supply().clearDriverAmmoTimers(vehicle);
            if (vehicle.getDriver() == gunner) {
                // 提交显式停车命令，避免 allow_drive 关闭后遗留上 tick 控制输入。
                ACTIONS.movement().apply(gunner, vehicle, ACTIONS.movement().stopCommand());
            }
            gunner.clearDriverRideState();
        }

        tickCountermeasure(gunner, vehicle, profile);
        tickEcmActive(gunner, vehicle);
        // 地面载具被红外导弹锁定：抛烟雾并开进烟雾停车（仅司机 AI）
        if (driverAi) {
            tickSmokeEvasion(gunner, vehicle);
        }
        // 调用雷达动作适配器，维持本车和外置雷达锁定边界。
        ACTIONS.radar().maintainLocalLock(vehicle, weaponUnit, target);
        ACTIONS.radar().maintainExternalLock(gunner, vehicle, weaponUnit, target, driverAi);
        // 调用制导动作适配器，维持 GPS、照射及在途 HITL 控制源。
        ACTIONS.guidance().maintain(gunner, vehicle, weaponUnit, target);

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
                if (weaponUnit != null && target == null) {
                    // gunnerlock 诊断：索敌无目标是"只锁定（中继）/不攻击"的直接信号——
                    // engage 只在 target!=null 时被调用。记录档案/索敌半径/驾驶员模式/难度，
                    // 一次日志即可区分 target_types 不匹配、创造模式保护等拒因。
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        RVP_GunnerLockDebug.logNoTarget(vehicle,
                                "profile=" + gunner.getProfileId()
                                        + " searchRadius=" + String.format("%.0f", profile.getSearchRadius())
                                        + " driverMode=" + describeDriverMode(vehicle)
                                        + " difficulty=" + vehicle.level().getDifficulty());
                    }
                }
                gunner.setControlledWeaponIndex(-1);
            }
        }

        // 周期监控（仅客户端有效）。必须按 dist 隔离调用：该类引用了 Minecraft/LocalPlayer 等
        // 客户端专属类，服务端若加载该类会在类加载验证阶段连带解析这些类并被 RuntimeDistCleaner 拦截崩溃。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RVP_GunnerDebugMonitor.onTick(gunner, vehicle, weaponUnit, target);
        }
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
            // 调用本类组网记账入口仅处理目标切换，避免 CIWS 每 tick 重扫时反复延长软窗口。
            markEngagementNetOnTrack(gunner, vehicle, ciwsTarget, profile);
            gunner.setTrackedTarget(ciwsTarget);
            return ciwsTarget;
        }

        if (gunner.tickCount % profile.getScanIntervalTick() == 0) {
            Entity best = GunnerTargeting.findBestTarget(gunner, vehicle, weaponUnit, profile);
            if (best != null) {
                // 调用本类组网记账入口覆盖新目标等待持锁/冷却的窗口。
                markEngagementNetOnTrack(gunner, vehicle, best, profile);
            }
            gunner.setTrackedTarget(best);
        }
        Entity tracked = gunner.getTrackedTarget();
        if (tracked == null || !tracked.isAlive()) {
            // 搜索中继指示（2026-09-16 搜索中继定版）：自身索敌（RCS 门控）无结果时，
            // 回退取搜索中继（如 96L6）的当前接触作为 trackedTarget——仅驱动炮口转向
            // （engage 的 weaponUnit.aim），不落锁（搜索中继不写锁）故 prepareLaunchLock
            // 的 RF 授权失败、不会发射；目标进入本车雷达烧穿距离后由 maintainLocalLock
            // 落锁，解锁发射授权（RADAR_LOCK 亦从此才有）。
            Entity designated = GunnerExternalRadarController.getRelaySearchContact(vehicle);
            if (designated != null
                    // 指示目标必须过与索敌相同的保护判定（2026-09-16 修复）：搜索中继接触链
                    // 不做创造过滤，直接采纳会让创造+非困难玩家经本车落锁被攻击
                    && GunnerTargeting.isValidDesignationTarget(gunner, vehicle, designated, profile)) {
                gunner.setTrackedTarget(designated);
                return designated;
            }
            gunner.setTrackedTarget(null);
            return null;
        }
        return tracked;
    }

    /**
     * 新目标开始跟踪时记录组网排斥窗口，覆盖选中后延迟开火的时间段；同一目标周期性重扫描不续窗。
     */
    private static void markEngagementNetOnTrack(GunnerEntity gunner, AbstractVehicle vehicle,
                                                  Entity target, GunnerProfile profile) {
        if (target == null || target.getId() == gunner.getTrackedTargetId()) {
            return;
        }
        // 调用本项目组网窗口策略，按当前交战距离生成同一套软窗口时长。
        long windowTick = RVP_GunnerEngagementNet.resolveWindowTick(
                vehicle, target, profile.getEngagementNetCooldownTick());
        if (windowTick > 0L) {
            // 调用本项目组网表记录新开始跟踪的目标，防止同阵营炮车在等待持锁时重复占用该来袭弹。
            RVP_GunnerEngagementNet.markTracked(vehicle.level(), gunner.getProfileFaction(), target, windowTick);
        }
    }

    /** gunnerlock 诊断用：驾驶员游戏模式描述（creative/spectator/survival/无驾驶员）。 */
    private static String describeDriverMode(AbstractVehicle vehicle) {
        if (vehicle.getDriver() instanceof Player player) {
            if (player.isSpectator()) {
                return "spectator";
            }
            return player.isCreative() ? "creative" : "survival";
        }
        return "none";
    }

    private static boolean isCiwsAltitudeMet(AbstractVehicle vehicle) {
        double agl = vehicle.getY() - vehicle.level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.util.Mth.floor(vehicle.getX()),
                net.minecraft.util.Mth.floor(vehicle.getZ()));
        return agl >= 50.0;
    }

    private static void tickCombat(GunnerEntity gunner, WeaponUnit weaponUnit, Entity target, GunnerProfile profile) {
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        boolean launcher = vehicle != null && hasLauncherDeployConfig(vehicle);
        // 调用武器动作适配器，原子执行瞄准、选弹、锁定/制导准备、发射和冷却推进。
        ACTIONS.weapons().engage(gunner, weaponUnit, target, profile, launcher);
    }

    private static boolean tickDriving(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Entity target, GunnerProfile profile) {
        RVP_GunnerMovementActions.Command command = ACTIONS.movement().stopCommand();
        boolean allowFire = true;
        if (vehicle instanceof FixedWingVehicle fixedWingVehicle) {
            allowFire = tickFixedWingDriving(gunner, fixedWingVehicle, target, profile, command);
            // 调用移动动作适配器，将固定翼算法输出一次性写入本体 ControlUnit。
            ACTIONS.movement().apply(gunner, vehicle, command);
            return allowFire;
        }
        if (vehicle instanceof RotaryWingVehicle rotaryWingVehicle) {
            allowFire = tickRotaryDriving(gunner, rotaryWingVehicle, target, profile, command);
            // 调用移动动作适配器，将旋翼算法输出一次性写入本体 ControlUnit。
            ACTIONS.movement().apply(gunner, vehicle, command);
            return allowFire;
        }
        if (hasLauncherDeployConfig(vehicle)) {
            tickLauncherGroundDriving(gunner, vehicle, target, profile, command);
            // 调用移动动作适配器，将发射架停车或转移命令一次性写入本体 ControlUnit。
            ACTIONS.movement().apply(gunner, vehicle, command);
            return true;
        }
        tickGroundDriving(gunner, vehicle, target, profile, command);
        // 调用移动动作适配器，将普通地面算法输出一次性写入本体 ControlUnit。
        ACTIONS.movement().apply(gunner, vehicle, command);
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
    private static void tickLauncherGroundDriving(GunnerEntity gunner, AbstractVehicle vehicle, @Nullable Entity target,
                                                  GunnerProfile profile, RVP_GunnerMovementActions.Command command) {
        boolean hasAmmo = hasAnyAmmo(vehicle);
        if (hasAmmo) {
            // Park — stop and let weapon system aim freely
            gunner.clearTacticalEvade();
            return;
        }
        // No ammo — reloading, roam tactically
        if (target == null) {
            tickGroundWander(gunner, vehicle, profile, command);
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
            command.backward = true;
            if (yawDelta > 0) command.right = true;
            else command.left = true;
            return;
        }
        if (gunner.hasTacticalHoldTicks()) return;
        if (gunner.hasTacticalEvadeTicks()) {
            tickGroundTacticalEvade(gunner, vehicle, target, yawDelta, command);
            return;
        }

        if (yawDelta > 8) command.right = true;
        else if (yawDelta < -8) command.left = true;
        if (dist > stopDist * 1.2 && Math.abs(yawDelta) < 80) command.forward = true;
    }

    private static boolean hasAnyAmmo(AbstractVehicle vehicle) {
        // 调用武器动作适配器，按运行时类型/数据能力排除反制武器后检查作战弹药。
        return ACTIONS.weapons().hasCombatAmmo(vehicle);
    }

    private static void tickGroundDriving(GunnerEntity gunner, AbstractVehicle vehicle, Entity target,
                                          GunnerProfile profile, RVP_GunnerMovementActions.Command command) {
        // 烟雾躲避停车：开进烟雾停车直到烟雾消散（优先于正常驾驶）
        if (gunner.hasSmokeHoldTicks()) {
            tickSmokeHoldDrive(gunner, vehicle, command);
            return;
        }
        if (target == null) {
            gunner.clearTacticalEvade();
            tickGroundWander(gunner, vehicle, profile, command);
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
            command.backward = true;
            if (yawDelta > 0) {
                command.right = true;
            } else {
                command.left = true;
            }
            return;
        }

        if (gunner.hasTacticalHoldTicks()) {
            return;
        }

        if (gunner.hasTacticalEvadeTicks()) {
            tickGroundTacticalEvade(gunner, vehicle, target, yawDelta, command);
            return;
        }

        if (yawDelta > 8) {
            command.right = true;
        } else if (yawDelta < -8) {
            command.left = true;
        }

        if (dist > stopDist * 1.2 && Math.abs(yawDelta) < 80) {
            command.forward = true;
        }
    }

    private static void tickGroundTacticalEvade(GunnerEntity gunner, AbstractVehicle vehicle, Entity target,
                                                float targetYawDelta, RVP_GunnerMovementActions.Command command) {
        Vec3 away = vehicle.position().subtract(target.position());
        if (away.horizontalDistanceSqr() < 1.0E-4) {
            away = vehicle.getLookAngle();
        }
        Vec2 awayRot = VectorUtil.vecToRot(new Vec3(away.x, 0, away.z));
        float desiredYaw = awayRot.y + gunner.getTacticalEvadeYawBias();
        float evadeYawDelta = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());

        if (evadeYawDelta > 8.0F) {
            command.right = true;
        } else if (evadeYawDelta < -8.0F) {
            command.left = true;
        }

        if (Math.abs(evadeYawDelta) < 100.0F) {
            command.forward = true;
        } else {
            command.backward = true;
        }

        if (Math.abs(targetYawDelta) > 60.0F) {
            if (targetYawDelta > 0.0F) {
                command.right = true;
            } else {
                command.left = true;
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
        boolean hasSmoke = ACTIONS.defense().hasCountermeasure(vehicle, RVP_EnumCountermeasureType.SMOKE);
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
        if (RVP_DebugFlags.GUNNER.isEnabled() && (gunner.tickCount + vehicle.getId()) % 100 == 0) {
            LOGGER.info("[RVP-Gunner-DEBUG] 载具={} tick={} smoke扫描: threat={}({}) hasSmoke={}",
                    vehicle.getVehicleId(), gunner.tickCount,
                    threat == null ? "null" : threat.getId(),
                    threat == null ? "null" : threat.getActiveGuidanceType(), hasSmoke);
        }
        // 调用防御动作适配器，将 Smoke 请求交给 RVP 服务端状态机权威校验。
        RVP_GunnerActionResult fireResult = ACTIONS.defense()
                .fireCountermeasure(vehicle, RVP_EnumCountermeasureType.SMOKE);
        if (fireResult != RVP_GunnerActionResult.DISPATCHED) {
            return;
        }
        gunner.setSmokeHoldTicks(SMOKE_HOLD_TICKS);
        if (RVP_DebugFlags.GUNNER.isEnabled()) {
            LOGGER.info("[RVP-Gunner] 载具={} 因{}，抛烟雾并停车", vehicle.getVehicleId(), reason);
        }
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
    private static void tickSmokeHoldDrive(GunnerEntity gunner, AbstractVehicle vehicle,
                                           RVP_GunnerMovementActions.Command command) {
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
                command.right = true;
            } else if (yawDelta < -8.0F) {
                command.left = true;
            }
            if (Math.abs(yawDelta) < 80.0F) {
                command.forward = true;
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

    private static boolean tickFixedWingDriving(GunnerEntity gunner, FixedWingVehicle vehicle,
                                                @Nullable Entity target, GunnerProfile profile,
                                                RVP_GunnerMovementActions.Command command) {
        if (target == null) {
            tickFixedWingCruise(gunner, vehicle, profile, false, command);
            return false;
        }
        boolean allowFire = ensureAirPhase(gunner, vehicle, profile);
        boolean attackPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK;
        tickFixedWingCruise(gunner, vehicle, profile, attackPhase, command);

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
        command.yRot = desiredRot.y;
        command.yRotKeep = false;

        command.forward = true;

        if (vehicle.onGround()) {
            float groundYawDelta = Mth.wrapDegrees(desiredRot.y - vehicle.getYRot());
            if (groundYawDelta > 6) {
                command.rightYaw = true;
            } else if (groundYawDelta < -6) {
                command.leftYaw = true;
            }
        }
        return allowFire;
    }

    private static boolean tickRotaryDriving(GunnerEntity gunner, RotaryWingVehicle vehicle,
                                             @Nullable Entity target, GunnerProfile profile,
                                             RVP_GunnerMovementActions.Command command) {
        if (target == null) {
            tickRotaryCruise(gunner, vehicle, profile, false, command);
            return false;
        }
        boolean allowFire = ensureRotaryAirPhase(gunner, profile);
        boolean attackPhase = gunner.getAirPhase() == AIR_PHASE_ATTACK;
        tickRotaryCruise(gunner, vehicle, profile, attackPhase, command);

        double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
        double currentAgl = vehicle.getY() - groundY;
        double takeoffAgl = Math.min(profile.getRotaryCruiseAltitudeMin(), 25.0);
        if (currentAgl < takeoffAgl) {
            command.setHoverMode = true;
            command.hoverMode = true;
            command.up = true;
            command.yRotKeep = true;
            command.xRotKeep = true;
            return false;
        }

        command.setHoverMode = true;
        command.hoverMode = false;
        Vec3 delta = target.position().subtract(vehicle.position());
        double horizontalDist = new Vec3(delta.x, 0, delta.z).length();
        Vec3 facingVec = attackPhase ? delta : vehicle.position().subtract(target.position());
        if (facingVec.horizontalDistanceSqr() < 1.0E-4) {
            facingVec = vehicle.getLookAngle();
        }
        Vec2 facingRot = VectorUtil.vecToRot(facingVec);
        Vec2 targetRot = VectorUtil.vecToRot(delta);
        command.yRot = facingRot.y;
        command.yRotKeep = false;
        command.xRotKeep = false;

        double stopDist = target instanceof AbstractVehicle ? profile.getDriveStopDistance() : 0.0;
        float desiredPitch = computeRotaryPitch(vehicle, profile, attackPhase, currentAgl,
                horizontalDist, stopDist, targetRot, command);
        command.xRot = desiredPitch;
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

    private static void tickFixedWingCruise(GunnerEntity gunner, FixedWingVehicle vehicle,
                                            GunnerProfile profile, boolean attackPhase,
                                            RVP_GunnerMovementActions.Command command) {
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
        command.forward = true;
        command.xRot = pitchCmd;
        command.xRotKeep = false;
    }

    private static void tickRotaryCruise(GunnerEntity gunner, RotaryWingVehicle vehicle,
                                         GunnerProfile profile, boolean attackPhase,
                                         RVP_GunnerMovementActions.Command command) {
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
            command.up = true;
        } else if (altitudeError > 2.0) {
            command.up = true;
        } else if (altitudeError < -2.0) {
            command.down = true;
        }
    }

    private static void tickGroundWander(GunnerEntity gunner, AbstractVehicle vehicle, GunnerProfile profile,
                                         RVP_GunnerMovementActions.Command command) {
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
                    command.right = true;
                } else {
                    command.left = true;
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

        command.forward = true;
        if ((gunner.tickCount / 40) % 2 == 0) {
            command.left = true;
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
                                            double currentAgl, double horizontalDist, double stopDist, Vec2 targetRot,
                                            RVP_GunnerMovementActions.Command command) {
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
            command.up = true;
        }
        if (currentAgl < hardLowAgl || descentRate < -0.35) {
            desiredPitch = Math.min(desiredPitch, -8.0F);
            command.up = true;
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

        // 调用防御动作适配器，使本体式反制武器也经过统一瞄准/发射边界。
        RVP_GunnerActionResult result = ACTIONS.defense().fireBaseCountermeasure(gunner, vehicle, threat);
        if (result == RVP_GunnerActionResult.DISPATCHED) {
            gunner.setCountermeasureCooldown(profile.getCountermeasureCooldownTick());
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

    /** 记录 gunner 主动ECM 决策（控制台 + logs/rvp_ecm_server.log，单客户端同目录）。 */
    private static void rvpEcmDbg(AbstractVehicle vehicle, String msg) {
        if (!RVP_DebugFlags.ECM.isEnabled()) {
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
        // 仍遵守敌我识别：只有"敌对"来袭导弹才触发（ENEMY gunner 不会被判为放置者的友方，
        // 见 RVP_EcmIff.areVehiclesFriendly 的 faction 守卫），不会对所有导弹无差别触发。
        if (!shouldFire) {
            // 取所有 ECM 设备中的最大干扰半径作为触发阈值（弹药/载具干扰半径与 200m 兜底）
            double triggerRadius = 200.0;
            for (BoneEcmActiveConfig cfg : devices.values()) {
                triggerRadius = Math.max(triggerRadius, cfg.ammoJamRadius());
                triggerRadius = Math.max(triggerRadius, cfg.vehicleJamRadius());
            }
            // findAmmoThreat 内部用 isFriendlyAmmoOwner 判定敌我（RVP 导弹已纳入 isDangerousAmmo）
            AmmoEntity threat = GunnerTargeting.findAmmoThreat(gunner, vehicle, triggerRadius, triggerRadius, 600.0);
            // 调试：统计触发半径内弹药/敌对危险数量，定位"为何不触发"
            int near = 0, hostileDanger = 0;
            AmmoEntity sample = null;
            for (Entity e : GunnerTargeting.collectTargetEntities(vehicle, triggerRadius, ent -> ent instanceof AmmoEntity ammo
                    && ammo.isAlive() && ammo.vehicle != vehicle)) {
                AmmoEntity ammo = (AmmoEntity) e;
                if (ammo.position().distanceToSqr(vehicle.position()) > triggerRadius * triggerRadius) {
                    continue;
                }
                near++;
                if (!GunnerTargeting.isFriendlyAmmoOwner(gunner, vehicle, vehicle.getTeam(), gunner.getTeam(), ammo.getOwner())
                        && GunnerTargeting.isDangerousAmmo(ammo)) {
                    hostileDanger++;
                    if (sample == null) {
                        sample = ammo;
                    }
                }
            }
            rvpEcmDbg(vehicle, "扫描: tr=" + (int) triggerRadius + " 半径内弹药=" + near
                    + " 敌对危险=" + hostileDanger
                    + (sample != null ? " 样本#" + sample.getId()
                        + " friendly=" + GunnerTargeting.isFriendlyAmmoOwner(gunner, vehicle, vehicle.getTeam(), gunner.getTeam(), sample.getOwner())
                        + " danger=" + GunnerTargeting.isDangerousAmmo(sample) : "")
                    + " findAmmoThreat=" + (threat != null ? "有#" + threat.getId() : "null"));
            if (threat != null) {
                shouldFire = true;
            }
        }
        if (!shouldFire) {
            return;
        }
        rvpEcmDbg(vehicle, "触发释放主动ECM");
        // 调用防御动作适配器，由 RVP 服务端 ECM 管理器校验冷却并返回真实结果。
        if (vehicle.level() instanceof ServerLevel) {
            ACTIONS.defense().fireActiveEcm(vehicle);
        }
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
            if (radarSource == null || ACTIONS.weapons().findAntiRadiationWeaponIndex(weaponUnit) < 0) {
                return false;
            }
            // 入口判定：若锁定我的雷达已在反辐射弹射击门控内，立即先射 1 枚（不等），再进复仇
            // 调用武器动作适配器，原子执行 AntiRadiation 锁定准备、制导准备、发射与冷却。
            RVP_GunnerActionResult immediate = ACTIONS.weapons()
                    .fireAntiRadiation(gunner, weaponUnit, radarSource);
            if (immediate == RVP_GunnerActionResult.DISPATCHED) {
                gunner.setSeadImmediateFired(true);
            }
            // 抛干扰物（箔条对抗雷达锁定）并进入飞离阶段
            // 调用防御动作适配器，将 Chaff 请求交给 RVP 服务端状态机。
            ACTIONS.defense().fireCountermeasure(vehicle, RVP_EnumCountermeasureType.CHAFF);
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
        RVP_GunnerMovementActions.Command command = ACTIONS.movement().stopCommand();
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
        command.yRot = desiredRot.y;
        command.yRotKeep = false;
        if (vehicle instanceof FixedWingVehicle fixedWing) {
            // 固定翼：前飞 + 高度保持（复用巡航的高度控制思路，取适中巡航高度）
            double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
            double desiredAlt = groundY + 180.0;
            double altErr = desiredAlt - vehicle.getY();
            float pitchCmd = (float) Mth.clamp(-altErr * 0.25, -18.0, 10.0);
            command.forward = true;
            command.xRot = pitchCmd;
            command.xRotKeep = false;
        } else if (vehicle instanceof RotaryWingVehicle rotary) {
            // 旋翼：悬停转向 + 高度保持
            command.setHoverMode = true;
            command.hoverMode = false;
            double groundY = EntityUtil.getGroundY(vehicle.level(), vehicle.position());
            double desiredAlt = groundY + 60.0;
            if (vehicle.getY() < desiredAlt - 4.0) {
                command.up = true;
            } else if (vehicle.getY() > desiredAlt + 4.0) {
                command.down = true;
            }
        }
        // 调用移动动作适配器，将本 tick SEAD 飞行动作一次性写入本体 ControlUnit。
        ACTIONS.movement().apply(gunner, vehicle, command);
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

    /** SEAD 复仇发射判定：复仇一发尚未打出、冷却结束且门控通过时发射。 */
    private static boolean tryFireRevenge(GunnerEntity gunner, WeaponUnit weaponUnit, Entity target) {
        if (gunner.isSeadRevengeFired()) {
            return false;
        }
        // 调用武器动作适配器，仅在 AntiRadiation 事务已提交时推进复仇状态。
        if (ACTIONS.weapons().fireAntiRadiation(gunner, weaponUnit, target)
                == RVP_GunnerActionResult.DISPATCHED) {
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

    /**
     * 供 RVP_GunnerDebugMonitor 使用，返回 selectWeaponIndex 的结果。
     * 仅在监控 dump 中指示是否有可用武器，不产生实际开火副作用。
     */
    static int findWeaponIndexForDump(WeaponUnit weaponUnit, Entity target) {
        // 调用武器动作适配器的只读选择入口，避免调试监控复制选择算法。
        return ACTIONS.weapons().findWeaponIndex(weaponUnit, target, null);
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
