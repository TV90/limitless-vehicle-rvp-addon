package org.ywzj.rvp.weapon.core;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.debug.RVP_WeaponOriginDebug;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.config.RVP_SightFireDisguiseConfig;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CGpsStateSync;
import org.ywzj.rvp.sight.RVP_SightFireDisguise;
import org.ywzj.rvp.sight.RVP_ScopeViewStateTable;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.gps.GPSTarget;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.function.Supplier;

/**
 * Factory for RVP projectile entities. It centralizes spawn-time data expansion
 * so weapon classes stay focused on firing rules rather than entity wiring.
 */
public final class RVP_ProjectileSpawner {

    private RVP_ProjectileSpawner() {}

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, null, 1f, 0f);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, float powerScale, float extraSpread) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, null, powerScale, extraSpread);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, WeaponUnit weaponUnit, float powerScale, float extraSpread) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, weaponUnit, weaponUnit,
                powerScale, extraSpread, true);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, WeaponUnit weaponUnit, float powerScale,
                                       float extraSpread, boolean includeFireSpread) {
        return spawn(data, kind, entityType, vehicle, shooter, aim, lockTarget, weaponUnit, weaponUnit,
                powerScale, extraSpread, includeFireSpread);
    }

    public static RVP_BaseBullet spawn(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                       Supplier<EntityType<? extends Projectile>> entityType,
                                       AbstractVehicle vehicle, LivingEntity shooter, AimContext aim,
                                       Entity lockTarget, WeaponUnit weaponUnit, WeaponUnit launchUnit,
                                       float powerScale, float extraSpread, boolean includeFireSpread) {
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return null;
        }
        float spread = Math.max(includeFireSpread ? data.getInaccuracy() : 0f, 0f) + Math.max(extraSpread, 0f);
        float xRot = aim.direction.x + randomSpread(level, spread);
        float yRot = aim.direction.y + randomSpread(level, spread);
        Vec3 direction = VectorUtil.rotToVec(xRot, yRot).normalize();
        float muzzleSpeed = data.resolveMuzzleSpeed(kind);
        Vec3 motion = direction.scale(Math.max(muzzleSpeed * powerScale, 0.01f));
        Vec3 muzzle = RVP_AimContexts.muzzle(aim);
        // 观瞄视角射弹原点分离（rvp_sight_fire_disguise）：玩家处于本站观瞄视角开火时，
        // 实际出弹点覆盖为观瞄相机坐标（方向不变=炮管指向=准星方向，消除观瞄离炮闩枢轴的抵近偏差）；
        // 炮口位置本身不进 aimContext，本体枪口烟特效天然留在炮管。查不到有效状态即原样出弹。
        RVP_SightFireDisguise sightDisguise = resolveSightFireDisguise(vehicle, weaponUnit, shooter, level, muzzle);
        if (sightDisguise != null) {
            muzzle = sightDisguise.actualSpawn();
        }
        RVP_WeaponOriginDebug.noteSpawnInvocation(
                vehicle,
                new RVP_WeaponOriginDebug.ResourceRef(
                        data.getWeaponId() == null ? null : data.getWeaponId().toString(),
                        kind == null ? "<null>" : kind.name()
                ),
                weaponUnit,
                aim,
                muzzle,
                motion,
                xRot,
                yRot,
                includeFireSpread,
                powerScale,
                extraSpread
        );
        Vec3 designatedTarget = resolveLegacyDesignatedTarget(data, kind, shooter, level, aim);
        // 调用本项目统一生成核心：旧载具入口只负责解析散布/GPS/挂架上下文，实体接线只保留一份。
        RVP_ProjectileSpawnResult result = spawn(new RVP_ProjectileSpawnContext(
                level, data, kind, entityType.get(), vehicle, weaponUnit, launchUnit, shooter,
                muzzle, new RVP_BaseBullet.AimRot(xRot, yRot), motion, lockTarget, designatedTarget,
                data.isInheritVehicleVelocity(), weaponUnit != null, muzzle,
                RVP_ProjectileChunkLoadingPolicy.DEFAULT, sightDisguise));
        return result.projectile();
    }

    /**
     * 唯一的服务端弹体接线与入世核心。无载具投送必须直接构造 context 调用此方法，
     * 不会触发弹仓、热量、后坐力、武器站锁定或 GPS 消耗。
     */
    public static RVP_ProjectileSpawnResult spawn(RVP_ProjectileSpawnContext context) {
        RVP_BaseBullet projectile = RVP_ProjectileEntityFactory.create(
                context.weaponKind(), context.entityType(), context.level(), context.weaponData());
        if (projectile == null) {
            RVP_ProjectileSpawnResult.Status status = RVP_ProjectileEntityFactory.supports(context.weaponKind())
                    ? RVP_ProjectileSpawnResult.Status.ENTITY_CREATION_FAILED
                    : RVP_ProjectileSpawnResult.Status.UNSUPPORTED_KIND;
            return new RVP_ProjectileSpawnResult(status, null);
        }

        projectile.initFromWeapon(context.weaponData(), context.weaponKind(), context.sourceVehicle(),
                context.owner(), context.spawnPosition(), context.aim(), context.initialMotion());
        // 观瞄伪装数据必须在 addFreshEntity 前烙上：生成包（writeSpawnData）在客户端开始追踪时即读
        if (context.sightFireDisguise() != null) {
            projectile.rvp$applySightFireDisguise(context.sightFireDisguise());
        }
        projectile.setRemoteChunkPathEnabled(
                context.chunkLoadingPolicy() == RVP_ProjectileChunkLoadingPolicy.REMOTE_FIRE_SUPPORT);
        projectile.setShooterWeaponUnit(context.sourceWeaponUnit());
        projectile.initColdLaunch(context.launchWeaponUnit());
        if (context.launchWeaponUnit() != null && context.wireLaunchFrom() != null) {
            AimContext wireAim = new AimContext();
            wireAim.from = context.wireLaunchFrom();
            // 调用本项目线导挂接初始化：只临时重建所需管口，不把可变 AimContext 存入不可变 context。
            projectile.setWireLaunchUnit(context.launchWeaponUnit(), wireAim);
        }
        projectile.name = Component.translatable(context.weaponData().getName());
        if (context.bindProgrammableAirburst() && context.sourceWeaponUnit() != null) {
            int weaponIndex = context.sourceWeaponUnit().getCurrentWeapon()
                    .map(AbstractVehicleWeapon::getIndex).orElse(0);
            // 调用本项目可编程引信存储：只对明确携带真实武器单元的旧载具入口生效。
            projectile.bindProgrammableAirburstRange(context.sourceWeaponUnit(), weaponIndex);
        }

        applyLockTarget(projectile, context);
        if (context.designatedTarget() != null
                && (context.weaponData().usesGuidanceType(RVP_EnumGuidanceType.GPS)
                || projectile.getTargetPos() == null)) {
            // GPS 坐标在旧入口中具有高于实体锁定的优先级；炮火任务也必须覆盖无载具实体弹体。
            projectile.setTargetPos(context.designatedTarget());
        }
        if (context.inheritVehicleVelocity() && context.sourceVehicle() != null) {
            projectile.setDeltaMovement(projectile.getDeltaMovement().add(context.sourceVehicle().getDeltaMovement()));
        }
        projectile.finalizeSpawnOrientation(context.aim());

        RVP_ProjectileLifecycleDebug.noteSpawnReady(projectile, null);
        if (!context.level().addFreshEntity(projectile)) {
            return new RVP_ProjectileSpawnResult(
                    RVP_ProjectileSpawnResult.Status.ADD_TO_WORLD_REJECTED, projectile);
        }
        // 调用本项目动态路径加载器：仅在成功入世且最终速度已确定后预热飞行路径。
        projectile.primeDynamicChunkPath();
        return new RVP_ProjectileSpawnResult(RVP_ProjectileSpawnResult.Status.SPAWNED, projectile);
    }

    /**
     * 解析观瞄视角射弹原点分离：玩家状态表命中本站 + 站级配置启用 + 距离帽内，
     * 返回以观瞄相机为实际出弹点、炮口为伪装出发点的伪装数据；任一门不过返回 null（普通出弹）。
     */
    private static RVP_SightFireDisguise resolveSightFireDisguise(AbstractVehicle vehicle, WeaponUnit weaponUnit,
                                                                  LivingEntity shooter, ServerLevel level,
                                                                  Vec3 normalMuzzle) {
        if (weaponUnit == null || !(shooter instanceof ServerPlayer player)) {
            return null;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root == null) {
            return null;
        }
        RVP_ScopeViewStateTable.ScopeState state = RVP_ScopeViewStateTable.get(player, level.getGameTime());
        if (state == null || state.vehicleId() != vehicle.getId()
                || stationIndexOf(vehicle, root) != state.partUnitIndex()) {
            return null;
        }
        if (!(root.getData() instanceof WeaponUnitDataExt ext) || ext.ywzj_rvp$getSightFireDisguise() == null) {
            return null;
        }
        RVP_SightFireDisguiseConfig config = ext.ywzj_rvp$getSightFireDisguise();
        // 观瞄相机坐标服务端现算（与本体 LocalVehiclePlayer SCOPE 分支同源；partialTick=1 取当前 tick）
        Vec3 sightMuzzle = root.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                ? root.worldOwnerViewPosition(1.0f)
                : root.worldOpticalSightPosition(1.0f);
        if (sightMuzzle.distanceTo(vehicle.position()) > config.maxDistance()) {
            return null;
        }
        return new RVP_SightFireDisguise(normalMuzzle, sightMuzzle, config.disguiseTicks(), config.blendTicks());
    }

    /** 求武器站在 getPartUnits() 中的序号（与客户端 RVP_ScopeViewSyncClient 同一套站序语义）。 */
    private static int stationIndexOf(AbstractVehicle vehicle, WeaponUnit root) {
        for (int i = 0; i < vehicle.getPartUnits().size(); i++) {
            if (vehicle.getPartUnits().get(i) == root) {
                return i;
            }
        }
        return -1;
    }

    private static void applyLockTarget(RVP_BaseBullet projectile, RVP_ProjectileSpawnContext context) {
        Entity lockTarget = context.lockTarget();
        if (lockTarget == null) return;
        RVP_WeaponData data = context.weaponData();
        // 目的：视线类制导（SACLOS 视线指令 / LBR 驾束）不接收发射锁定——光束与视线严格跟随
        // 操作手鼠标瞄准线，弹体不持 targetEntity（否则 tickGuidance 实体追踪与近炸锁定分支
        // 会让导弹偏向发射时锁定的目标）；ARH/SARH/IR/LH/SALH/TV 等其他弹型传锁不变。
        if (data.isLineOfSightGuided()) {
            return;
        }
        // 兜底归一：锁到载具乘员时改为所属载具，保持原载具射击与反制判定语义。
        Entity lockVehicle = lockTarget.getVehicle();
        if (lockVehicle instanceof AbstractVehicle) lockTarget = lockVehicle;
        projectile.setTargetEntity(lockTarget);
        if (projectile instanceof RVP_MissileEntity missile
                && (data.usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || data.usesGuidanceType(RVP_EnumGuidanceType.AIR))) {
            missile.rvp$setActiveSeekerDesignatedTarget(lockTarget);
            projectile.setTargetPos(lockTarget.getBoundingBox().getCenter());
        }
        if (context.weaponKind() == RVP_EnumWeaponKind.MISSILE
                && data.usesGuidanceType(RVP_EnumGuidanceType.IR)) {
            projectile.setTargetPos(lockTarget.getBoundingBox().getCenter());
            projectile.markLaunchTargetSnapshot();
            projectile.beginIrSeekerGrace(Math.max(6, data.resolveGuidanceScanIntervalTick() * 2));
        }
    }

    private static Vec3 resolveLegacyDesignatedTarget(RVP_WeaponData data, RVP_EnumWeaponKind kind,
                                                       LivingEntity shooter, ServerLevel level, AimContext aim) {
        GPSTarget gps = data.usesGuidanceType(RVP_EnumGuidanceType.GPS)
                ? GPSTargetManager.consumeAssignedTarget(shooter, level.dimension().location()) : null;
        if (gps != null && gps.dimension().equals(level.dimension().location())) {
            if (shooter instanceof ServerPlayer player) {
                // 调用本项目 GPS 同步包：旧载具入口消费目标后立即把剩余列表回传给该玩家。
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(GPSTargetManager.snapshot(player)),
                        player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
            }
            return gps.pos();
        }
        return kind == RVP_EnumWeaponKind.MISSILE ? RVP_AimContexts.impactPoint(aim) : null;
    }

    private static float randomSpread(Level level, float spread) {
        if (spread <= 0f) {
            return 0f;
        }
        return (level.random.nextFloat() - 0.5f) * spread;
    }

    /**
     * Random pitch/yaw offset (degrees) for one shotgun volley center; pellets add {@code canister_diff} on top.
     */
    public static float[] sampleSpreadCenter(Level level, float spreadDegrees) {
        return new float[]{
                randomSpread(level, spreadDegrees),
                randomSpread(level, spreadDegrees)
        };
    }

}
