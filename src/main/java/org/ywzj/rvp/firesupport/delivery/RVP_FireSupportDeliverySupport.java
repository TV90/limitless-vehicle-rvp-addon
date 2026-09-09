package org.ywzj.rvp.firesupport.delivery;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportSpawnChunkLeaseManager;
import org.ywzj.rvp.weapon.core.RVP_ProjectileChunkLoadingPolicy;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawnContext;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawnResult;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner;
import org.ywzj.vehicle.util.VectorUtil;

/** 地面、空中与垂直投送共用的确定性方向、租约和无载具生成辅助。 */
final class RVP_FireSupportDeliverySupport {
    /** 为入场方位扰动分配的独立随机盐。 */ private static final long HEADING_SALT = 0x4F1BBCDCBFA54001L;

    private RVP_FireSupportDeliverySupport() {}

    /** 把 Minecraft 方位角转换为水平飞行单位向量；0 指向 +Z。 */
    static Vec3 inboundDirection(RVP_FireSupportDeliveryContext context, double jitterDegrees) {
        double heading = context.inboundHeadingDegrees();
        if (jitterDegrees > 0.0D) {
            Random random = new Random(context.authoritativeSeed() ^ HEADING_SALT);
            heading += (random.nextDouble() * 2.0D - 1.0D) * jitterDegrees;
        }
        double radians = Math.toRadians(heading);
        return new Vec3(Math.sin(radians), 0.0D, Math.cos(radians)).normalize();
    }

    /** 在不加载区块的前提下判断 X/Z 是否位于世界边界内。 */
    static boolean withinBorder(ServerLevel level, double x, double z) {
        return level.getWorldBorder().isWithinBounds(
                new BlockPos(Mth.floor(x), level.getMinBuildHeight(), Mth.floor(z)));
    }

    /** @return 指定位置是否已加载且允许实体 Tick；方法不触发同步加载。 */
    static boolean entityTicking(ServerLevel level, double x, double z) {
        return level.isPositionEntityTicking(
                new BlockPos(Mth.floor(x), level.getMinBuildHeight(), Mth.floor(z)));
    }

    /** 为一发计划落点登记短期 Chunk 租约；任务结束前保留该落点引用。 */
    static RVP_FireSupportDeliveryResult leaseTarget(RVP_FireSupportDeliveryContext context,
                                                     double x, double z, int preloadTicks) {
        return lease(context, x, z, preloadTicks, RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose.TARGET);
    }

    /** 为当前发射/释放候选登记短期 Chunk 租约；候选放弃后可释放该引用。 */
    static RVP_FireSupportDeliveryResult leaseLaunchCandidate(RVP_FireSupportDeliveryContext context,
                                                              double x, double z, int preloadTicks) {
        return lease(context, x, z, preloadTicks,
                RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose.LAUNCH_CANDIDATE);
    }

    /** 按指定业务用途为一个生成/高度查询位置登记短期 Chunk 租约。 */
    private static RVP_FireSupportDeliveryResult lease(RVP_FireSupportDeliveryContext context,
                                                        double x, double z, int preloadTicks,
                                                        RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose purpose) {
        ChunkPos chunk = new ChunkPos(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus status =
                RVP_FireSupportSpawnChunkLeaseManager.request(context.level(), context.missionId(), chunk,
                        context.scheduledTick(), preloadTicks, context.maxLoadedChunksPerMission(), purpose);
        return switch (status) {
            case READY -> result(RVP_FireSupportDeliveryResult.Status.PREPARED, null, null);
            case TOO_EARLY, PRELOADING -> result(RVP_FireSupportDeliveryResult.Status.TOO_EARLY, null, null);
            case WAITING_FOR_CHUNK -> result(RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null);
            case MISSION_CHUNK_LIMIT -> result(RVP_FireSupportDeliveryResult.Status.CHUNK_LIMIT_EXCEEDED, null, null);
            case TIMED_OUT -> result(RVP_FireSupportDeliveryResult.Status.CHUNK_WAIT_TIMED_OUT, null, null);
            case INVALID_REQUEST -> result(RVP_FireSupportDeliveryResult.Status.INVALID_CONTEXT, null, null);
        };
    }

    /** 合并两个租约结果；致命失败优先，其次等待，两个都准备完成才返回 PREPARED。 */
    static RVP_FireSupportDeliveryResult combine(RVP_FireSupportDeliveryResult first,
                                                 RVP_FireSupportDeliveryResult second) {
        if (fatal(first.status())) return first;
        if (fatal(second.status())) return second;
        if (first.status() == RVP_FireSupportDeliveryResult.Status.RETRY_LATER
                || second.status() == RVP_FireSupportDeliveryResult.Status.RETRY_LATER) {
            // 目的：保留“当前姿态暂不可投放”的诊断语义；调度器会保留武器池并沿后续参考点重试。
            return result(RVP_FireSupportDeliveryResult.Status.RETRY_LATER, null, null);
        }
        if (first.status() == RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK
                || second.status() == RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK) {
            return result(RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null);
        }
        if (!first.prepared() || !second.prepared()) {
            return result(RVP_FireSupportDeliveryResult.Status.TOO_EARLY, null, null);
        }
        return result(RVP_FireSupportDeliveryResult.Status.PREPARED, null, null);
    }

    /** 使用统一无载具生成核心创建类型化 RVP 弹体。 */
    static RVP_FireSupportDeliveryResult spawn(RVP_FireSupportDeliveryContext context, Vec3 spawn,
                                               Vec3 initializedMotion, Vec3 spawnContextMotion,
                                               RVP_ProjectileChunkLoadingPolicy chunkPolicy) {
        if (initializedMotion.lengthSqr() <= 1.0E-12D || spawnContextMotion.lengthSqr() <= 1.0E-12D) {
            return result(RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, spawn);
        }
        var rotation = VectorUtil.vecToRot(initializedMotion.normalize());
        RVP_BaseBullet.AimRot aim = new RVP_BaseBullet.AimRot(rotation.x, rotation.y);
        // 调用本项目无载具生成核心；不伪造载具、武器站、锁定目标或载机速度继承。
        RVP_ProjectileSpawnResult spawnResult = RVP_ProjectileSpawner.spawn(new RVP_ProjectileSpawnContext(
                context.level(), context.weaponData(), context.weaponData().getWeaponKind(), null,
                context.sourceVehicle(), null, null, context.owner(), spawn, aim, spawnContextMotion,
                null, context.designatedTarget(), context.inheritVehicleVelocity(), false, null, chunkPolicy));
        return spawnResult.spawned()
                ? result(RVP_FireSupportDeliveryResult.Status.DELIVERED, spawnResult.projectile(), spawn)
                : result(RVP_FireSupportDeliveryResult.Status.SPAWN_FAILED, spawnResult.projectile(), spawn);
    }

    /** 在真实弹体成功生成后保留其发射/释放 Chunk 引用，避免后续候选回退错误回收。 */
    static void confirmLaunch(RVP_FireSupportDeliveryContext context, Vec3 spawn) {
        ChunkPos chunk = new ChunkPos(Mth.floor(spawn.x) >> 4, Mth.floor(spawn.z) >> 4);
        RVP_FireSupportSpawnChunkLeaseManager.confirmLaunch(context.level(), context.missionId(), chunk);
    }

    static RVP_FireSupportDeliveryResult result(RVP_FireSupportDeliveryResult.Status status,
                                                RVP_BaseBullet projectile, Vec3 spawn) {
        return new RVP_FireSupportDeliveryResult(status, projectile, spawn);
    }

    private static boolean fatal(RVP_FireSupportDeliveryResult.Status status) {
        return status != RVP_FireSupportDeliveryResult.Status.PREPARED
                && status != RVP_FireSupportDeliveryResult.Status.TOO_EARLY
                && status != RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK
                && status != RVP_FireSupportDeliveryResult.Status.RETRY_LATER;
    }
}
