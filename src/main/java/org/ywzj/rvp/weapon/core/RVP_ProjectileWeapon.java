package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CEnterHitlView;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.ahead.RVP_AheadProgrammer;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadShape;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.util.RVP_CanisterGridUtil;
import org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;

import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Projectile-spawning weapon for missile, rocket, machinegun and bomb entries.
 */
public class RVP_ProjectileWeapon extends RVP_WeaponBase {

    /** 按配置创建 RVP 类型化弹体的实体类型。 */
    private final Supplier<EntityType<? extends Projectile>> entityType;

    /** 本轮点射使用的发射点；独立副本避免扣弹裁剪修改调用方列表。 */
    private List<AimContext> burstVolleyAimContexts;
    /** 本轮点射的操作者，供后续服务端 Tick 重新检查发射条件。 */
    private LivingEntity burstVolleyShooter;

    /** 本次发射通过检查后的目标快照；无锁允许发射时 target 可以为空，整个记录为空表示拒射。 */
    protected record ApprovedShot(
            /** 实际出弹的武器部件。 */ WeaponUnit launchUnit,
            /** 提供火控与锁定的根武器站。 */ WeaponUnit rootUnit,
            /** 已通过本次发射检查的目标。 */ @Nullable Entity target) {}

    public RVP_ProjectileWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data,
                                String serializeId, Supplier<EntityType<? extends Projectile>> entityType) {
        super(vehicle, unit, index, data, serializeId);
        this.entityType = entityType;
    }

    @Override
    public void tick() {
        super.tick();
        if (getVehicle().level().isClientSide()) {
            return;
        }
        if (getData().getFireData().getFireMode() != RVP_EnumFireMode.BURST) {
            return;
        }
        getFireController().tickBurstVolley(this::fireBurstVolleyShot);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        noteServerShootInvocation(aimContexts, shooter);
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isReloading()) {
            return false;
        }
        RVP_FireData fire = getData().getFireData();
        if (fire.isSelfDestructVehicle()
                && getData().getWeaponKind() == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.BOMB) {
            // 自爆只执行一次，不进入 BURST/齐射调度；拒绝时不扣弹、不加热。
            if (isCoolingDown() || !getFireController().canShootHeat() || getRemainAmmo() <= 0
                    || aimContexts == null || aimContexts.isEmpty()) return false;
            getFireController().primeServerShot();
            if (!canShootOnServer(shooter)) return false;
            boolean fired = RVP_VehicleSelfDestructService.detonate(getVehicle(), shooter, getData(),
                    () -> consumeAmmo(new ArrayList<>(List.of(aimContexts.get(0)))),
                    () -> setRemainAmmo(getRemainAmmo() + 1));
            if (fired) {
                this.lastShootTime = System.currentTimeMillis();
                getFireController().onShotFired();
            }
            return fired;
        }
        if (fire.getFireMode() == RVP_EnumFireMode.BURST) {
            return shootBurstRound(aimContexts, shooter, fire);
        }
        if (isCoolingDown() || !getFireController().canShootHeat()) {
            return false;
        }
        getFireController().primeServerShot();
        if (!canShootOnServer(shooter)) {
            return false;
        }
        // 统一入口先检查目标，再扣弹并生成；拒射必须向本体返回 false，禁止广播成功开火。
        if (!dispatchShots(aimContexts, shooter)) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        // 只有实际生成弹体才记录热量和消耗射击模式状态。
        getFireController().onShotFired();
        return true;
    }

    /**
     * One burst round: fire the first shot immediately, then continue on server ticks.
     */
    private boolean shootBurstRound(List<AimContext> aimContexts, LivingEntity shooter, RVP_FireData fire) {
        var controller = getFireController();
        if (!controller.canStartBurstRound(fire)) {
            return false;
        }
        if (isCoolingDown()) {
            return false;
        }
        if (!controller.canShootHeat()) {
            return false;
        }
        controller.primeServerShot();
        if (!canShootOnServer(shooter)) {
            return false;
        }
        // 点射首发也必须经过同一检查与生成入口，失败时不启动后续连发。
        if (!dispatchShots(aimContexts, shooter)) {
            return false;
        }

        burstVolleyAimContexts = new ArrayList<>(aimContexts);
        burstVolleyShooter = shooter;
        this.lastShootTime = System.currentTimeMillis();

        // 首发确实生成后才记录热量。
        controller.recordHeatForShot();

        int burstCount = fire.getBurstCount();
        if (burstCount > 1) {
            controller.startBurstVolleyAfterFirstShot(fire, getShootInterval());
        } else {
            controller.completeBurstRound(fire);
        }
        return true;
    }

    private boolean fireBurstVolleyShot() {
        if (burstVolleyAimContexts == null || burstVolleyShooter == null) {
            return false;
        }
        // [RVP] 炮管损坏门控（2026-09-28）：canShootOnServer 只拦首发起，连发续发路径绕过它——
        // 连发中段炮管被打坏须在此中断剩余弹（completeBurstRound 结束本轮）
        if (!passesBarrelDamageGate(burstVolleyShooter)) {
            return false;
        }
        // 每一发重新检查当前锁定；脱锁拒射时交由控制器结束连发，不能继续扣弹或加热。
        if (!dispatchShots(burstVolleyAimContexts, burstVolleyShooter)) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        return true;
    }

    /**
     * Ahead 引信：锁定目标仅是装订空爆引信（增强空爆弹幕效果），并非发射前提。
     * 未锁定/未装订时弹丸照常发射，像普通机炮一样直射（命中目标造成直击伤害），
     * 装订距离由 {@link RVP_AheadProgrammer#programForShot} 在 {@code dispatchShots} 内解算存储，
     * 弹丸生成时经 {@code bindProgrammableAirburstRange} 读取后于飞行中空爆释放弹幕。
     */
    private boolean dispatchShots(List<AimContext> aimContexts, LivingEntity shooter) {
        if (aimContexts == null || aimContexts.isEmpty()) {
            // 无出弹点也是发射失败，必须在本体 consumeAmmo 的空列表路径前拦截。
            noteLaunchResult("SHOT_REJECTED", "NO_AIM_CONTEXT", 0);
            return false;
        }
        // 先完成所有可能拒射的检查，并将本次使用的锁定目标固定下来。
        ApprovedShot approved = prepareShot(aimContexts, shooter);
        if (approved == null) {
            return false;
        }
        List<AimContext> shots = new ArrayList<>(aimContexts);
        // 调用本体扣弹：弹量不足齐射时只裁剪本次副本，保持后续连发原始出弹点不变。
        if (!consumeAmmo(shots)) {
            noteLaunchResult("SHOT_REJECTED", "NO_AMMO", 0);
            return false;
        }
        // 蓄力在检查通过后才消费；所有实体生成失败时恢复蓄力。
        int chargeBefore = getChargeTick();
        float chargeScale = consumeChargeScale();
        RVP_WeaponData data = getData();
        // 调用原点诊断：此处才是已获准、即将真正生成弹体的发射请求。
        RVP_WeaponOriginDebug.noteDispatchInvocation(this, approved.launchUnit(), approved.rootUnit(),
                shooter, shots, chargeScale);
        int fired = 0;
        for (AimContext aim : shots) {
            // 每个出弹点消费一枚弹药；霰弹/子弹束至少成功生成一个实体才算该枚发射成功。
            if (spawnShot(data, shooter, aim, approved, chargeScale)) {
                fired++;
                // 仅实际出弹才施加本体后坐力。
                applyShotRecoil(data);
            } else {
                // 工厂构造失败或世界拒绝加入实体时退还该发弹药，不能只看是否拿到 Java 对象。
                setRemainAmmo(getRemainAmmo() + 1);
            }
        }
        if (fired == 0) {
            // 恢复本项目的蓄力计数，调用方不会推进冷却、热量或开火成功回包。
            setChargeTick(chargeBefore);
        }
        noteLaunchResult(fired > 0 ? "SHOT_FIRED" : "SHOT_REJECTED",
                fired == shots.size() ? "SPAWNED" : "SPAWN_FAILED", fired);
        return fired > 0;
    }

    /** 发射前检查：只读取本次目标并提示拒射原因，不扣弹、不消费蓄力、不生成实体。 */
    @Nullable
    protected ApprovedShot prepareShot(List<AimContext> aimContexts, LivingEntity shooter) {
        RVP_WeaponData data = getData();
        WeaponUnit launchUnit = getWeaponUnit();
        WeaponUnit rootUnit = launchUnit.getRootParentWeaponUnit();

        // [RVP] 鱼雷瞄水发射门（2026-10-02，torpedo_data.require_aim_water）：沿武器站瞄准方向
        // 做射线检测（固体+流体），require_aim_water_range 内必须命中水方块才允许发射——
        // 防止瞄着陆地/舰桥误发。拒绝不耗弹（与下方离轴终检同语义），动作栏提示。
        if (data.getWeaponKind() == org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind.TORPEDO
                && data.getTorpedoData().isRequireAimWater()
                && shooter.level() instanceof net.minecraft.server.level.ServerLevel torpedoAimLevel) {
            if (!passesTorpedoAimWaterGate(torpedoAimLevel, data, shooter, aimContexts)) {
                if (shooter instanceof net.minecraft.server.level.ServerPlayer notifyPlayer) {
                    notifyPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("ui.rvp.torpedo_not_aiming_water"), true);
                }
                noteLaunchResult("SHOT_REJECTED", "NOT_AIMING_WATER", 0);
                return null;
            }
        }

        Entity lock = null;
        if (data.isHomingProjectile()
                && (!data.isVehicleLaserGuided() && !data.isCommandGuided() || data.isSaclosTvGuided())) {
            // [RVP] 2026-10-21 IR 反泄漏收口（方案 §6 L3）：IR 发射武器的导弹目标只认
            // 服务端 armed 表（客户端跃迁上报经包线复算写入 / AI 炮手服务端自算 dwell 写入）。
            // 表空即拒射（不耗弹，提示 ui.rvp.ir_seeker_not_armed）——宁可拒射不可放行。
            if (org.ywzj.rvp.guidance.RVP_IrLockHelper.isIrLaunchWeapon(data)) {
                lock = RVP_ServerIrSeekerArmedTable.getArmedEntity(rootUnit, data.getWeaponId());
                if (lock == null && data.isRequireLock()) {
                    if (shooter instanceof net.minecraft.server.level.ServerPlayer notifyPlayer) {
                        notifyPlayer.displayClientMessage(net.minecraft.network.chat.Component
                                .translatable("ui.rvp.ir_seeker_not_armed"), true);
                    }
                    noteLaunchResult("SHOT_REJECTED", "IR_NOT_ARMED", 0);
                    return null;
                }
            } else {
                lock = RVP_WeaponSensorHelper.effectiveOrStatic(rootUnit) == WeaponUnitData.FireControlSensorType.RF
                        ? RVP_RadarRoleHelper.getEffectiveRfLockedEntity(rootUnit)
                        : rootUnit.getLockedEntity();
            }
        }
        // 雷达/导引头要求锁定时复查服务端目标，堵住客户端发包后目标失效的窗口；保留现有 EO 直瞄豁免。
        if (requiresServerSeekerLock(data, lock, RVP_WeaponSensorHelper.effectiveOrStatic(rootUnit))) {
            if (shooter instanceof net.minecraft.server.level.ServerPlayer notifyPlayer) {
                notifyPlayer.displayClientMessage(net.minecraft.network.chat.Component
                        .translatable("ui.need_lock_entity"), true);
            }
            noteLaunchResult("SHOT_REJECTED", "RADAR_LOCK_LOST", 0);
            return null;
        }
        // 服务端离轴角权威终检（2026-09-20 离轴角 bug 修复，bug B 兜底）：IR 发射武器的
        // 锁可能来自 EO 捕获/RF 落锁/切武器恢复——这些来源都不查离轴角，改包客户端也可能
        // 直接发。以离轴基准方向复验，超锥拒射（不耗弹，提示 ui.ir_target_off_axis）。
        if (lock != null && shooter.level() instanceof net.minecraft.server.level.ServerLevel) {
            if (!org.ywzj.rvp.guidance.RVP_IrLockHelper.isLaunchTargetWithinOffAxis(rootUnit, lock, data, 1.0f)) {
                if (shooter instanceof net.minecraft.server.level.ServerPlayer notifyPlayer) {
                    notifyPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("ui.ir_target_off_axis"), true);
                }
                noteLaunchResult("SHOT_REJECTED", "TARGET_OFF_AXIS", 0);
                return null;
            }
        }
        // 反舰导引头服务端权威终检（2026-10-05 用户需求）：anti_ship_seeker 弹的锁定目标
        // RCS 综合值不足 SCAN_SEA_RCS_THRESHOLD（500）即拒射（不耗弹）——防改包客户端或
        // EO 直瞄路径绕过客户端门；无锁定（lock==null）时 HOMING 弹本无末端制导基准，同样拒射。
        if (data.getGuidanceData().isAntiShipSeeker()
                && shooter.level() instanceof net.minecraft.server.level.ServerLevel
                && !(lock instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle antiShipTarget
                && org.ywzj.rvp.radar.RVP_AspectRcs.combinedFactor(antiShipTarget, shooter.position())
                >= org.ywzj.rvp.radar.RVP_RadarScanHelper.SCAN_SEA_RCS_THRESHOLD)) {
            if (shooter instanceof net.minecraft.server.level.ServerPlayer notifyPlayer) {
                notifyPlayer.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("ui.rvp.target_rcs_below_sea_threshold"), true);
            }
            noteLaunchResult("SHOT_REJECTED", "TARGET_RCS_TOO_SMALL", 0);
            return null;
        }
        return new ApprovedShot(launchUnit, rootUnit, lock);
    }

    /**
     * 服务端导引头锁定终检：发射段依赖导引头接管（IR/AIR/ARH/SARH）且 require_lock 的弹
     * 必须有存活目标锁，否则拒射且不耗弹。
     *
     * <p>2026-10-31 修复 GPS 弹被误拒：{@code isRadarHoming()} 经 {@code usesGuidanceType} 把
     * 末端阶段的 ARH 也算作雷达制导，使 YJ-20（GPS + 末端 ARH）、RGM-109 这类弹在无锁时被
     * 本门拒射，提示 {@code ui.need_lock_entity}（本体语言为“导引头需要先锁定目标”）。
     * 现按发射段制导类型判定，与客户端 {@link RVP_WeaponBase#requiresEntityLock} 同口径；
     * EO 直瞄豁免保持不变。</p>
     */
    static boolean requiresServerSeekerLock(RVP_WeaponData data, @Nullable Entity lock,
                                            WeaponUnitData.FireControlSensorType sensor) {
        return data.launchPhaseRequiresSeekerLock() && data.isRequireLock()
                && (lock == null || !lock.isAlive())
                && sensor != WeaponUnitData.FireControlSensorType.EO;
    }

    /** 发射诊断也覆盖实体尚未生成的阶段，沿用生命周期日志的开关和文件。 */
    private void noteLaunchResult(String event, String reason, int fired) {
        // 记录武器、发射站、剩余弹药及原因，便于区分拒射和飞行途中销毁。
        RVP_ProjectileLifecycleDebug.noteLaunchResult(this, event, reason, fired);
    }

    /** 对本次已经批准的单个出弹点生成弹体，返回是否至少一枚成功加入世界。 */
    protected boolean spawnShot(RVP_WeaponData data, LivingEntity shooter, AimContext aim,
                                ApprovedShot approved, float chargeScale) {
        WeaponUnit rootUnit = approved.rootUnit();
        WeaponUnit launchUnit = approved.launchUnit();
        Entity lock = approved.target();

        int armPreselectVehicleId = -1;
        int armPreselectRadarIndex = -1;
        Vec3 armPreselectPos = null;
        if (data.isAntiRadiationMissile() && rootUnit != null) {
            armPreselectVehicleId = RVP_WeaponLockStateTable.getArmPreselectedVehicleId(rootUnit);
            armPreselectRadarIndex = RVP_WeaponLockStateTable.getArmPreselectedRadarIndex(rootUnit);
            armPreselectPos = RVP_WeaponLockStateTable.getArmPreselectedPos(rootUnit);
        }

        if (data.getFireData().isCanister()) {
            // 霰弹只按真实成功加入世界的弹丸计数。
            return shootCanister(data, shooter, aim, lock, rootUnit, launchUnit, chargeScale);
        }
        // 普通弹体携带本次目标快照与反辐射预选信息。
        return shootProjectiles(data, shooter, aim, lock, rootUnit, launchUnit, chargeScale,
                armPreselectVehicleId, armPreselectRadarIndex, armPreselectPos);
    }

    /** 发射成功后把后坐力交给本体物理引擎。 */
    protected void applyShotRecoil(RVP_WeaponData data) {
        getVehicle().physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
    }

    /**
     * [RVP] 鱼雷瞄水发射门（2026-10-02）：从武器站瞄准起点沿瞄准方向射线检测
     * （ClipContext.Block.COLLIDER + Fluid.ANY——固体方块与流体都可终止射线），
     * {@code require_aim_water_range} 内命中水流体方块才放行；命中固体（瞄着舰桥/陆地）
     * 不放行。无瞄准上下文时不拦截（防御：正常发射链必有 AimContext）。
     */
    private boolean passesTorpedoAimWaterGate(net.minecraft.server.level.ServerLevel level,
                                              RVP_WeaponData data, LivingEntity shooter,
                                              List<AimContext> aimContexts) {
        if (aimContexts == null || aimContexts.isEmpty()) {
            return true;
        }
        AimContext aim = aimContexts.get(0);
        Vec3 origin = aim.from != null ? aim.from : shooter.getEyePosition();
        // 调用本体向量工具：AimContext 的 Vec2（xRot/yRot）转世界方向向量，与弹体初速同源
        Vec3 direction = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
        double range = data.getTorpedoData().getRequireAimWaterRange();
        Vec3 end = origin.add(direction.scale(range));
        net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(
                origin, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.ANY,
                shooter));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return false;
        }
        return level.getFluidState(hit.getBlockPos()).is(net.minecraft.tags.FluidTags.WATER);
    }

    private boolean shootProjectiles(RVP_WeaponData data, LivingEntity shooter, AimContext aim,
                                  Entity lock, WeaponUnit rootUnit, WeaponUnit launchUnit, float chargeScale,
                                  int armPreselectVehicleId, int armPreselectRadarIndex, Vec3 armPreselectPos) {
        int totalProjectiles = data.getFireData().getCanisterCount() * data.getFireData().getCanisterBurstCount();
        if (RVP_AheadProgrammer.isAheadWeapon(data)) {
            RVP_AheadProgrammer.programForShot(getVehicle(), rootUnit, getIndex(), data, aim, 1.0f);
        }
        boolean spawned = false;
        for (int i = 0; i < totalProjectiles; i++) {
            RVP_BaseBullet projectile = RVP_ProjectileSpawner.spawn(
                    data, data.getWeaponKind(), entityType, getVehicle(), shooter, aim, lock,
                    rootUnit, launchUnit, chargeScale, getBarrelDamageSpreadExtra(), true);
            if (armPreselectVehicleId >= 0 && projectile != null) {
                projectile.setPreselectedTarget(armPreselectVehicleId, armPreselectRadarIndex);
                if (armPreselectPos != null) {
                    projectile.setAntiRadiationSignalAcquired(false);
                    projectile.setTargetPos(armPreselectPos);
                    projectile.rememberGuidancePos(armPreselectPos);
                }
            }
            spawned |= projectile != null;
            maybeEnterHitlView(data, shooter, projectile, i);
        }
        return spawned;
    }

    private boolean shootCanister(RVP_WeaponData data, LivingEntity shooter, AimContext aim,
                               Entity lock, WeaponUnit rootUnit, WeaponUnit launchUnit, float chargeScale) {
        RVP_FireData fire = data.getFireData();
        int pellets = Math.max(fire.getCanisterCount(), 1);
        int total = pellets * fire.getCanisterBurstCount();
        int[][] gridCells = fire.getCanisterShape() == RVP_EnumSpreadShape.SQUARE
                ? RVP_CanisterGridUtil.assignCells(pellets, fire.getCanisterDistribution())
                : null;
        float spread = data.getInaccuracy();
        float[] centerOffset = new float[]{0f, 0f};
        boolean spawned = false;
        for (int i = 0; i < total; i++) {
            int pelletIndex = i % pellets;
            if (pelletIndex == 0) {
                centerOffset = RVP_ProjectileSpawner.sampleSpreadCenter(getVehicle().level(), spread);
            }
            AimContext pelletAim = canisterAim(aim, pelletIndex, gridCells, fire, centerOffset);
            if (RVP_AheadProgrammer.isAheadWeapon(data)) {
                RVP_AheadProgrammer.programForShot(getVehicle(), rootUnit, getIndex(), data, pelletAim, 1.0f);
            }
            RVP_BaseBullet projectile = RVP_ProjectileSpawner.spawn(
                    data, data.getWeaponKind(), entityType, getVehicle(), shooter, pelletAim, lock,
                    rootUnit, launchUnit, chargeScale, getBarrelDamageSpreadExtra(), false);
            spawned |= projectile != null;
            maybeEnterHitlView(data, shooter, projectile, i);
        }
        return spawned;
    }

    private AimContext canisterAim(AimContext base, int pelletIndex, int[][] gridCells, RVP_FireData fire,
                                   float[] centerOffset) {
        AimContext out = new AimContext();
        Vec3 muzzle = RVP_AimContexts.muzzle(base);
        out.from = muzzle;
        out.position = base.position;
        float xRot = base.direction.x + centerOffset[0];
        float yRot = base.direction.y + centerOffset[1];
        float diff = fire.getCanisterDiff();
        int type = fire.getCanisterType();
        var random = getVehicle().level().random;
        var distribution = fire.getCanisterDistribution();
        var footprint = fire.getCanisterShape();
        boolean useGrid = gridCells != null && footprint == RVP_EnumSpreadShape.SQUARE;

        if (type == 0) {
            float[] offset = new float[3];
            if (useGrid) {
                RVP_SpreadDistributionUtil.sampleCanisterGridPosition(
                        gridCells[pelletIndex], fire.getCanisterCount(), diff, offset);
            } else {
                RVP_SpreadDistributionUtil.sampleCanisterPosition(random, distribution, footprint, diff, offset);
            }
            out.from = muzzle.add(offset[0], offset[1], offset[2]);
        } else {
            float[] angular = new float[2];
            if (useGrid) {
                RVP_SpreadDistributionUtil.sampleCanisterGridAngular(
                        gridCells[pelletIndex], fire.getCanisterCount(), diff, angular);
            } else {
                RVP_SpreadDistributionUtil.sampleCanisterAngular(random, distribution, footprint, diff, angular);
            }
            xRot += angular[0];
            yRot += angular[1];
            if (type == 2 && fire.getCanisterBurstDelayTime() > 0f) {
                Vec3 dir = VectorUtil.rotToVec(xRot, yRot).normalize();
                out.from = muzzle.add(dir.scale(pelletIndex * fire.getCanisterBurstDelayTime()));
            }
        }

        out.direction = new Vec2(xRot, yRot);
        return out;
    }

    private void maybeEnterHitlView(RVP_WeaponData data, LivingEntity shooter, RVP_BaseBullet projectile, int projectileIndex) {
        if (!(projectile instanceof RVP_MissileEntity) || projectileIndex != 0 || !data.hasHumanInTheLoop()) {
            return;
        }
        if (getVehicle().level().isClientSide() || !(shooter instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        RVP_EnumHitlControlMode mode = data.getGuidanceData().getGuidanceType() == RVP_EnumGuidanceType.HITL_CLOS_TV
                ? RVP_EnumHitlControlMode.MOUSE
                : RVP_EnumHitlControlMode.DESIGNATE;
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                S2CEnterHitlView.of(projectile.getId(), mode));
    }
}
