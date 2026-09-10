package org.ywzj.rvp.firesupport.delivery;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportSpawnChunkLeaseManager;
import org.ywzj.rvp.weapon.core.RVP_ProjectileEntityFactory;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawnContext;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawnResult;
import org.ywzj.rvp.weapon.core.RVP_ProjectileChunkLoadingPolicy;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.util.VectorUtil;

/** 首版垂直入场真实弹体投送；只依赖服务端世界和公共 RVP 弹体链。 */
public final class RVP_VerticalProjectileDelivery implements RVP_FireSupportDelivery {
    /** 已严格解析且冻结的投送参数。 */ private final RVP_FireSupportDeliveryTypes.VerticalProjectileData data;

    public RVP_VerticalProjectileDelivery(RVP_FireSupportDeliveryTypes.VerticalProjectileData data) {
        if (data == null) throw new IllegalArgumentException("垂直投送参数不能为空");
        this.data = data;
    }

    @Override
    public net.minecraft.resources.ResourceLocation typeId() {
        return RVP_FireSupportDeliveryTypes.VERTICAL_PROJECTILE;
    }

    @Override
    public int preloadTicks() {
        return data.preloadTicks();
    }

    @Override
    public RVP_FireSupportDeliveryResult prepare(RVP_FireSupportDeliveryContext context) {
        int blockX = Mth.floor(context.impactPoint().x());
        int blockZ = Mth.floor(context.impactPoint().z());
        return leaseResult(context, new ChunkPos(blockX >> 4, blockZ >> 4));
    }

    @Override
    public RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context) {
        RVP_WeaponData weapon = context.weaponData();
        RVP_EnumWeaponKind kind = weapon.getWeaponKind();
        if (!RVP_ProjectileEntityFactory.supports(kind)) {
            return result(RVP_FireSupportDeliveryResult.Status.UNSUPPORTED_WEAPON, null, null,
                    "RVP 实体工厂不支持武器 kind；weapon=" + weapon.getWeaponId() + ", kind=" + kind);
        }
        int blockX = Mth.floor(context.impactPoint().x());
        int blockZ = Mth.floor(context.impactPoint().z());
        BlockPos borderProbe = new BlockPos(blockX, context.level().getMinBuildHeight(), blockZ);
        if (!context.level().getWorldBorder().isWithinBounds(borderProbe)) {
            return result(RVP_FireSupportDeliveryResult.Status.OUTSIDE_WORLD_BORDER, null, null,
                    "垂直投送落点超出世界边界；impact=" + context.impactPoint() + ", probe=" + borderProbe);
        }

        ChunkPos chunk = new ChunkPos(blockX >> 4, blockZ >> 4);
        RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus lease = RVP_FireSupportSpawnChunkLeaseManager.request(
                context.level(), context.missionId(), chunk, context.scheduledTick(),
                data.preloadTicks(), context.maxLoadedChunksPerMission(),
                RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose.TARGET);
        switch (lease) {
            case TOO_EARLY, PRELOADING -> {
                return result(RVP_FireSupportDeliveryResult.Status.TOO_EARLY, null, null);
            }
            case WAITING_FOR_CHUNK -> {
                return result(RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null,
                        leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
            }
            case MISSION_CHUNK_LIMIT -> {
                return result(RVP_FireSupportDeliveryResult.Status.CHUNK_LIMIT_EXCEEDED, null, null,
                    leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
            }
            case TIMED_OUT -> {
                return result(RVP_FireSupportDeliveryResult.Status.CHUNK_WAIT_TIMED_OUT, null, null,
                    leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
            }
            case INVALID_REQUEST -> {
                return result(RVP_FireSupportDeliveryResult.Status.INVALID_CONTEXT, null, null,
                    leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
            }
            case READY -> { }
        }

        // Chunk 已达到 entity-ticking 后才调用权威高度图，避免该查询同步生成远程区块。
        int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        // 高地上方空间不足时钳制到世界顶端内侧，避免正常山地因固定入场高度被直接拒绝投送。
        double spawnY = Math.min(groundY + data.spawnHeightAboveImpactMeters(),
                context.level().getMaxBuildHeight() - 1.0);
        if (spawnY <= groundY || spawnY < context.level().getMinBuildHeight()) {
            return result(RVP_FireSupportDeliveryResult.Status.OUTSIDE_BUILD_HEIGHT, null, null,
                    "垂直投送没有有效生成高度；groundY=" + groundY + ", spawnY=" + spawnY
                            + ", minBuildHeight=" + context.level().getMinBuildHeight()
                            + ", maxBuildHeight=" + context.level().getMaxBuildHeight()
                            + ", configuredHeight=" + data.spawnHeightAboveImpactMeters());
        }
        Vec3 spawn = new Vec3(context.impactPoint().x(), spawnY, context.impactPoint().z());
        Vec3 direction = resolveEntryDirection(context.authoritativeSeed(), context.roundIndex(), data.headingJitterDegrees());
        double speed = data.entrySpeedMetersPerTick() > 0
                ? data.entrySpeedMetersPerTick() : weapon.resolveMuzzleSpeed(kind);
        if (!Double.isFinite(speed) || speed <= 0) {
            return result(RVP_FireSupportDeliveryResult.Status.UNSUPPORTED_WEAPON, null, spawn,
                    "垂直投送初速无效；weapon=" + weapon.getWeaponId() + ", kind=" + kind
                            + ", configuredSpeed=" + data.entrySpeedMetersPerTick()
                            + ", resolvedMuzzleSpeed=" + weapon.resolveMuzzleSpeed(kind));
        }
        Vec3 motion = direction.scale(speed);
        Vec2 rot = VectorUtil.vecToRot(direction);
        RVP_BaseBullet.AimRot aim = new RVP_BaseBullet.AimRot(rot.x, rot.y);
        // 调用本项目无载具生成核心：sourceVehicle/sourceWeaponUnit/launchUnit 均为空，明确跳过载具副作用。
        RVP_ProjectileSpawnResult spawnResult = RVP_ProjectileSpawner.spawn(new RVP_ProjectileSpawnContext(
                context.level(), weapon, kind, null, null, null, null, context.owner(), spawn, aim, motion,
                null, null, false, false, null, RVP_ProjectileChunkLoadingPolicy.REMOTE_FIRE_SUPPORT));
        if (!spawnResult.spawned()) {
            return result(RVP_FireSupportDeliveryResult.Status.SPAWN_FAILED, spawnResult.projectile(), spawn,
                    "垂直投送弹体加入世界失败；spawnStatus=" + spawnResult.status()
                            + ", projectile=" + (spawnResult.projectile() == null
                            ? "null" : spawnResult.projectile().getClass().getName())
                            + ", spawn=" + spawn);
        }
        return result(RVP_FireSupportDeliveryResult.Status.DELIVERED, spawnResult.projectile(), spawn,
                "垂直投送弹体已加入世界");
    }

    static Vec3 resolveEntryDirection(long seed, int roundIndex, double maxTiltDegrees) {
        if (maxTiltDegrees <= 0) return new Vec3(0, -1, 0);
        Random random = new Random(seed ^ (0x9E3779B97F4A7C15L * (roundIndex + 1L)));
        double tilt = Math.toRadians(maxTiltDegrees) * Math.sqrt(random.nextDouble());
        double azimuth = random.nextDouble() * Math.PI * 2.0;
        return new Vec3(Math.sin(tilt) * Math.cos(azimuth), -Math.cos(tilt),
                Math.sin(tilt) * Math.sin(azimuth)).normalize();
    }

    private static RVP_FireSupportDeliveryResult result(RVP_FireSupportDeliveryResult.Status status,
                                                         RVP_BaseBullet projectile, Vec3 spawn) {
        return new RVP_FireSupportDeliveryResult(status, projectile, spawn);
    }

    /** 创建包含阻塞原因的垂直投送结果。 */
    private static RVP_FireSupportDeliveryResult result(RVP_FireSupportDeliveryResult.Status status,
                                                         RVP_BaseBullet projectile, Vec3 spawn,
                                                         String diagnostic) {
        return new RVP_FireSupportDeliveryResult(status, projectile, spawn, diagnostic);
    }

    private RVP_FireSupportDeliveryResult leaseResult(RVP_FireSupportDeliveryContext context, ChunkPos chunk) {
        RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus lease = RVP_FireSupportSpawnChunkLeaseManager.request(
                context.level(), context.missionId(), chunk, context.scheduledTick(),
                data.preloadTicks(), context.maxLoadedChunksPerMission(),
                RVP_FireSupportSpawnChunkLeaseManager.LeasePurpose.TARGET);
        return switch (lease) {
            case READY -> result(RVP_FireSupportDeliveryResult.Status.PREPARED, null, null);
            case TOO_EARLY, PRELOADING -> result(RVP_FireSupportDeliveryResult.Status.TOO_EARLY, null, null);
            case WAITING_FOR_CHUNK -> result(RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null);
            case MISSION_CHUNK_LIMIT -> result(RVP_FireSupportDeliveryResult.Status.CHUNK_LIMIT_EXCEEDED, null, null,
                    leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
            case TIMED_OUT -> result(RVP_FireSupportDeliveryResult.Status.CHUNK_WAIT_TIMED_OUT, null, null,
                    leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
            case INVALID_REQUEST -> result(RVP_FireSupportDeliveryResult.Status.INVALID_CONTEXT, null, null,
                    leaseDiagnostic(context, chunk, data.preloadTicks(), lease));
        };
    }

    /** 把垂直投送的目标 Chunk 租约状态转换为详细阻塞原因。 */
    private static String leaseDiagnostic(RVP_FireSupportDeliveryContext context, ChunkPos chunk,
                                           int preloadTicks,
                                           RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus status) {
        return "垂直投送 Chunk 租约阻止投送；leaseStatus=" + status + ", purpose=TARGET, chunk=" + chunk
                + ", now=" + context.level().getGameTime() + ", scheduledTick=" + context.scheduledTick()
                + ", preloadTicks=" + preloadTicks
                + ", maxMissionChunks=" + context.maxLoadedChunksPerMission()
                + ", impact=" + context.impactPoint();
    }

}
