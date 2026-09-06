package org.ywzj.rvp.firesupport.server;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.firesupport.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.firesupport.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.weapon.core.RVP_ProjectileEntityFactory;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawnContext;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawnResult;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.Random;

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
    public RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context) {
        RVP_WeaponData weapon = context.weaponData();
        RVP_EnumWeaponKind kind = weapon.getWeaponKind();
        if (!RVP_ProjectileEntityFactory.supports(kind)) {
            return result(RVP_FireSupportDeliveryResult.Status.UNSUPPORTED_WEAPON, null, null);
        }
        int blockX = Mth.floor(context.impactPoint().x());
        int blockZ = Mth.floor(context.impactPoint().z());
        BlockPos borderProbe = new BlockPos(blockX, context.level().getMinBuildHeight(), blockZ);
        if (!context.level().getWorldBorder().isWithinBounds(borderProbe)) {
            return result(RVP_FireSupportDeliveryResult.Status.OUTSIDE_WORLD_BORDER, null, null);
        }

        ChunkPos chunk = new ChunkPos(blockX >> 4, blockZ >> 4);
        RVP_FireSupportSpawnChunkLeaseManager.LeaseStatus lease =
                RVP_FireSupportSpawnChunkLeaseManager.request(
                        context.level(), context.missionId(), chunk, context.expectedSpawnTick(),
                        data.preloadTicks(), context.maxLoadedChunksPerMission());
        switch (lease) {
            case TOO_EARLY, PRELOADING -> {
                return result(RVP_FireSupportDeliveryResult.Status.TOO_EARLY, null, null);
            }
            case WAITING_FOR_CHUNK -> {
                return result(RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null);
            }
            case MISSION_CHUNK_LIMIT -> {
                return result(RVP_FireSupportDeliveryResult.Status.CHUNK_LIMIT_EXCEEDED, null, null);
            }
            case TIMED_OUT -> {
                return result(RVP_FireSupportDeliveryResult.Status.CHUNK_WAIT_TIMED_OUT, null, null);
            }
            case INVALID_REQUEST -> {
                return result(RVP_FireSupportDeliveryResult.Status.INVALID_CONTEXT, null, null);
            }
            case READY -> { }
        }

        // Chunk 已达到 entity-ticking 后才调用权威高度图，避免该查询同步生成远程区块。
        int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        // 高地上方空间不足时钳制到世界顶端内侧，避免正常山地因固定入场高度被直接拒绝投送。
        double spawnY = Math.min(groundY + data.spawnHeightAboveImpactMeters(),
                context.level().getMaxBuildHeight() - 1.0);
        if (spawnY <= groundY || spawnY < context.level().getMinBuildHeight()) {
            return result(RVP_FireSupportDeliveryResult.Status.OUTSIDE_BUILD_HEIGHT, null, null);
        }
        Vec3 spawn = new Vec3(context.impactPoint().x(), spawnY, context.impactPoint().z());
        Vec3 direction = resolveEntryDirection(context.authoritativeSeed(), context.roundIndex(), data.headingJitterDegrees());
        double speed = data.entrySpeedMetersPerTick() > 0
                ? data.entrySpeedMetersPerTick() : weapon.resolveMuzzleSpeed(kind);
        if (!Double.isFinite(speed) || speed <= 0) {
            return result(RVP_FireSupportDeliveryResult.Status.UNSUPPORTED_WEAPON, null, spawn);
        }
        Vec3 motion = direction.scale(speed);
        Vec2 rot = VectorUtil.vecToRot(direction);
        RVP_BaseBullet.AimRot aim = new RVP_BaseBullet.AimRot(rot.x, rot.y);
        // 调用本项目无载具生成核心：sourceVehicle/sourceWeaponUnit/launchUnit 均为空，明确跳过载具副作用。
        RVP_ProjectileSpawnResult spawnResult = RVP_ProjectileSpawner.spawn(new RVP_ProjectileSpawnContext(
                context.level(), weapon, kind, null, null, null, null, context.owner(), spawn, aim, motion,
                null, null, false, false, null));
        if (!spawnResult.spawned()) {
            return result(RVP_FireSupportDeliveryResult.Status.SPAWN_FAILED, spawnResult.projectile(), spawn);
        }
        return result(RVP_FireSupportDeliveryResult.Status.DELIVERED, spawnResult.projectile(), spawn);
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
}
