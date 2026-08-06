package org.ywzj.rvp.radar;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.BulletEntity;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * B7 雷达行为近似（替代被删 {@code RadarUnitMixin} 中"炮手服务端扫描"部分）。
 *
 * <p>本体 {@link RadarUnit} 服务端扫描（{@code tickScan}，每 20 tick）只负责
 * RADAR_SEARCH 告警；玩家雷达的目标探测由客户端 {@code tickDetect} 完成并通过
 * DETECT 网络包回写服务端。但没有客户端驾驶的"炮手（{@link GunnerEntity}）"载具
 * 没有来源填充服务端雷达探测表，导致服务端雷达目标（导弹制导校验/武器锁定校验依赖
 * {@link RadarUnit#getDetectedEntities()}）为空。本服务在服务端低频（每 4 tick）扫描
 * 炮手驾驶载具的雷达，把目标写入本体雷达探测表。</p>
 *
 * <p>RVP 弹体：本体 {@link Radar#scanTargets} 的 {@code getBoundingBox().getSize() < 1}
 * 过滤会排除小体积弹体，且 {@code Entity.getBoundingBox()} 为 final 无法用虚拟箱
 * 覆写（原 {@code RadarSignatureMixin} 是 mixin @Redirect，不可复用）——因此 RVP 弹体
 * 一律由本服务 {@link #appendRvpAmmoTargets} 按"最大探测距离 × 信号尺寸"缩放直接补入
 * 雷达探测表，等效实现原 mixin 的 RVP 弹体探测/锁定能力。</p>
 *
 * <p>已知近似：原 mixin 每 tick 扫描，本服务每 4 tick（本体接触保持最短 100ms 可覆盖）；
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RadarScanService {

    private static final int SCAN_INTERVAL_TICK = 4;

    private RVP_RadarScanService() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        if (server.getTickCount() % SCAN_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level);
        }
    }

    private static void scanLevel(ServerLevel level) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            if (vehicle.isDestroyed() || !vehicle.hasPower()) {
                continue;
            }
            if (!(vehicle.getDriver() instanceof GunnerEntity)) {
                continue;
            }
            scanGunnerRadars(vehicle);
        }
    }

    private static void scanGunnerRadars(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof RadarUnit radar) || !radar.isOn()) {
                continue;
            }
            Vec3 radarPos = radar.worldRadarPosition();
            float yRotSpeed = radar.getYRotSpeed();
            float sectorHalf = radar.getScanSectorAngle() / 2.0f;
            float yMin = radar.getYRotMin();
            float yMax = radar.getYRotMax();
            List<Entity> targets = Radar.scanTargets(vehicle, radarPos, radar.getMaxScanDistance(), entityPos -> {
                if (!isWithinScanHeight(radar, entityPos)) {
                    return false;
                }
                Vec2 aimRot = radar.aimRot(entityPos);
                float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
                if (!isYawWithin(y, yMin, yMax)) {
                    return false;
                }
                if (yRotSpeed > 0f && Math.abs(y - radar.getYRot()) > yRotSpeed / 2.0f) {
                    return false;
                }
                return !(Math.abs(aimRot.x - radar.getXRot()) > sectorHalf);
            });
            filterUndetectableRvpAmmo(targets);
            appendRvpAmmoTargets(radar, targets, yRotSpeed > 0f);
            for (Entity target : targets) {
                radar.detect(target);
            }
        }
    }

    /**
     * 过滤不可被雷达探测的弹体：本体弹体（{@link BulletEntity} / {@link RVP_BulletEntity}）
     * 以及信号特征为 0 或机枪弹的 {@link RVP_BaseBullet}。与已删除
     * {@code RadarUnitMixin#ywzj_rvp$filterUndetectableRvpAmmo} 保持一致。
     */
    private static void filterUndetectableRvpAmmo(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        entities.removeIf(entity -> entity instanceof BulletEntity
                || entity instanceof RVP_BulletEntity
                || entity instanceof RVP_BaseBullet bullet && !bullet.isRadarDetectableAmmo());
    }

    /**
     * 把超过本体 {@link Radar#scanTargets} 体积阈值（{@code getSize() < 1}，即虚拟箱
     * 边长约 0.577 以下）的 RVP 弹体按"最大探测距离 × 信号尺寸"缩放补入目标表。
     * 与已删除 {@code RadarUnitMixin#ywzj_rvp$appendRvpAmmoTargets} 保持一致。
     */
    private static void appendRvpAmmoTargets(RadarUnit radar, List<Entity> entities, boolean requireTrackingLine) {
        Vec3 radarPos = radar.worldRadarPosition();
        double maxScanDistance = radar.getMaxScanDistance();
        double maxScanDistanceSqr = maxScanDistance * maxScanDistance;
        AABB scanBox = new AABB(radarPos.subtract(maxScanDistance, maxScanDistance, maxScanDistance),
                radarPos.add(maxScanDistance, maxScanDistance, maxScanDistance));
        Set<Integer> existingIds = new HashSet<>();
        for (Entity entity : entities) {
            existingIds.add(entity.getId());
        }
        List<RVP_BaseBullet> bullets = radar.getVehicle().level().getEntitiesOfClass(RVP_BaseBullet.class, scanBox, bullet -> {
            if (bullet == null || !bullet.isAlive() || bullet.getVehicle() != null) {
                return false;
            }
            if (!bullet.isRadarDetectableAmmo()) {
                return false;
            }
            float sig = bullet.getSignatureSize();
            double effectiveMaxSqr = maxScanDistanceSqr * sig * sig;
            Vec3 targetPos = bullet.getBoundingBox().getCenter();
            if (targetPos.distanceToSqr(radarPos) > effectiveMaxSqr) {
                return false;
            }
            if (!isWithinScanHeight(radar, targetPos)) {
                return false;
            }
            Vec2 aimRot = radar.aimRot(targetPos);
            float yMin = radar.getYRotMin();
            float yMax = radar.getYRotMax();
            float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!isYawWithin(y, yMin, yMax)) {
                return false;
            }
            if (requireTrackingLine && radar.getYRotSpeed() > 0f
                    && Math.abs(y - radar.getYRot()) > radar.getYRotSpeed() / 2.0f) {
                return false;
            }
            return !(Math.abs(aimRot.x - radar.getXRot()) > radar.getScanSectorAngle() / 2.0f);
        });
        for (RVP_BaseBullet bullet : bullets) {
            if (existingIds.add(bullet.getId())) {
                entities.add(bullet);
            }
        }
    }

    private static boolean isWithinScanHeight(RadarUnit radar, Vec3 targetPos) {
        float minHeight = 25f;
        float maxHeight = 10000f;
        RadarUnitData data = radar.getData();
        if (data instanceof RadarUnitDataExt ext) {
            minHeight = ext.ywzj_rvp$getScanMinHeight();
            maxHeight = ext.ywzj_rvp$getScanMaxHeight();
        }
        if (maxHeight < minHeight) {
            float t = minHeight;
            minHeight = maxHeight;
            maxHeight = t;
        }
        double groundY = radar.getVehicle().level().getHeight(Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(targetPos.x), (int) Math.floor(targetPos.z));
        double heightAboveGround = targetPos.y - groundY;
        return heightAboveGround >= minHeight && heightAboveGround <= maxHeight;
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

    private static boolean isYawWithin(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return true;
        }
        return yaw >= yMin && yaw <= yMax;
    }
}
