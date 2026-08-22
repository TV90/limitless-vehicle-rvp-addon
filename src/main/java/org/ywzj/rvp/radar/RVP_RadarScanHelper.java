package org.ywzj.rvp.radar;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_Decoy;
import org.ywzj.rvp.countermeasure.RVP_DecoyEntity;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.weapon.BulletEntity;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 雷达探测公共工具（RVP_RadarScanService 服务端扫描与
 * RVP_ClientRadarTickHandler 客户端玩家探测共用，保持过滤/补入逻辑一致）。
 *
 * <p>逻辑与被删 {@code RadarUnitMixin} 中同名私有方法一致：RVP 弹体按
 * "最大探测距离 × 信号尺寸"缩放补入雷达探测表，本体弹体与不可探测弹体被过滤。</p>
 */
public final class RVP_RadarScanHelper {

    private RVP_RadarScanHelper() {
    }

    /**
     * 雷达是否配置为仅扫描/跟踪载具（雷达参数 {@code scan_vehicle_only=true}）。
     * 为 true 时扫描与接触保活只保留 {@link org.ywzj.vehicle.entity.vehicle.AbstractVehicle}
     * 目标，排除弹药、箔条干扰物等非载具实体。
     */
    public static boolean isVehicleOnly(RadarUnit radar) {
        RadarUnitData data = radar.getData();
        return data instanceof RadarUnitDataExt ext && ext.ywzj_rvp$isScanVehicleOnly();
    }

    public static boolean isWithinScanHeight(RadarUnit radar, Vec3 targetPos) {
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

    public static float normalizeYawForLimits(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return yaw;
        }
        boolean prefer360Space = yMin >= 0.0f && yMax > 180.0f;
        if (prefer360Space && yaw < 0.0f) {
            return yaw + 360.0f;
        }
        return yaw;
    }

    public static boolean isYawWithin(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return true;
        }
        return yaw >= yMin && yaw <= yMax;
    }

    /**
     * 过滤不可被雷达探测的弹体：本体弹体（{@link BulletEntity} / {@link RVP_BulletEntity}）
     * 以及信号特征为 0 或机枪弹的 {@link RVP_BaseBullet}。
     */
    public static void filterUndetectableRvpAmmo(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        entities.removeIf(entity -> entity instanceof BulletEntity
                || entity instanceof RVP_BulletEntity
                || entity instanceof RVP_BaseBullet bullet && !bullet.isRadarDetectableAmmo());
    }

    /**
     * 把超过本体 {@code Radar.scanTargets}/{@code detectTargets} 体积阈值
     * （{@code getSize() < 1}）的 RVP 弹体按"最大探测距离 × 信号尺寸"缩放补入目标表。
     */
    public static void appendRvpAmmoTargets(RadarUnit radar, List<Entity> entities,
                                            Iterable<Entity> allEntities, boolean requireTrackingLine) {
        Vec3 radarPos = radar.worldRadarPosition();
        double maxScanDistance = radar.getMaxScanDistance();
        double maxScanDistanceSqr = maxScanDistance * maxScanDistance;
        Set<Integer> existingIds = new HashSet<>();
        for (Entity entity : entities) {
            existingIds.add(entity.getId());
        }
        // O(实体) 遍历已加载实体，替代 ±maxScanDistance 立方体 getEntitiesOfClass（O(箱子截面)，
        // 长程雷达 1000+ 格单次上百万截面，服务端/客户端掉 TPS）
        for (Entity entity : allEntities) {
            if (!(entity instanceof RVP_BaseBullet bullet)
                    || !bullet.isAlive() || bullet.getVehicle() != null
                    || !bullet.isRadarDetectableAmmo()) {
                continue;
            }
            float sig = bullet.getSignatureSize();
            double effectiveMaxSqr = maxScanDistanceSqr * sig * sig;
            Vec3 targetPos = bullet.getBoundingBox().getCenter();
            if (targetPos.distanceToSqr(radarPos) > effectiveMaxSqr) {
                continue;
            }
            if (!isWithinScanHeight(radar, targetPos)) {
                continue;
            }
            Vec2 aimRot = radar.aimRot(targetPos);
            float yMin = radar.getYRotMin();
            float yMax = radar.getYRotMax();
            float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!isYawWithin(y, yMin, yMax)) {
                continue;
            }
            if (requireTrackingLine && radar.getYRotSpeed() > 0f
                    && Math.abs(y - radar.getYRot()) > radar.getYRotSpeed() / 2.0f) {
                continue;
            }
            if (Math.abs(aimRot.x - radar.getXRot()) > radar.getScanSectorAngle() / 2.0f) {
                continue;
            }
            if (existingIds.add(bullet.getId())) {
                entities.add(bullet);
            }
        }
    }

    /**
     * 过滤不可被雷达扫描的干扰物：热焰弹（FLARE）不可被雷达扫描。
     * 箔条（CHAFF）保留（可被雷达扫描显示为接触）。
     */
    public static void filterRadarInvisibleDecoys(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        entities.removeIf(entity -> entity instanceof RVP_Decoy decoy
                && decoy.rvp$decoyType() == RVP_EnumCountermeasureType.FLARE);
    }

    /** 单次补入雷达目标表的箔条数量上限：箔条云一次可几十枚，超视距框全显示太晃眼，
     * 只保留离雷达最近的该数量（其余本帧不显示，下一帧重新筛）。 */
    private static final int MAX_DISPLAYED_CHAFF = 8;

    /**
     * 按已加载实体列表做雷达区域扫描（O(实体)）。替代本体 {@code Radar.scanTargets} 的
     * ±maxScanDistance 立方体 {@code getEntities}（O(箱子截面)，长程雷达 1024+ 单次数百万截面，
     * 服务端/客户端严重掉 TPS）。过滤规则与本体的 `scanTargets` 保持一致。
     *
     * @param allEntities 已加载实体清单（调用方从 ServerLevel / ClientLevel 的 getEntities().getAll() 取）
     */
    public static List<Entity> scanRadarArea(Iterable<Entity> allEntities, Entity radarOwner, Vec3 radarPos,
                                             double maxScanDistance, Function<Vec3, Boolean> check) {
        List<Entity> out = new ArrayList<>();
        double maxSqr = maxScanDistance * maxScanDistance;
        for (Entity entity : allEntities) {
            if (entity == radarOwner
                    || entity.getVehicle() != null
                    || !entity.isAlive()
                    || entity instanceof net.minecraftforge.entity.PartEntity<?>
                    || entity.getBoundingBox().getSize() < 1
                    || entity.distanceToSqr(radarOwner) > maxSqr) {
                continue;
            }
            if (Boolean.TRUE.equals(check.apply(entity.getBoundingBox().getCenter()))) {
                out.add(entity);
            }
        }
        return out;
    }

    /**
     * 把箔条干扰物（CHAFF）补入雷达目标表（可被扫描显示，不产生锁定）。
     * 干扰物碰撞箱小于本体扫描体积阈值，需按扫描包络（高度/方位/扇区）显式补入。
     * 数量超 {@link #MAX_DISPLAYED_CHAFF} 时只补最近的该数量，避免雷达界面被箔条云刷屏。
     */
    public static void appendRadarVisibleChaffDecoys(RadarUnit radar, List<Entity> entities,
                                                     Iterable<Entity> allEntities) {
        Vec3 radarPos = radar.worldRadarPosition();
        double maxScanDistance = radar.getMaxScanDistance();
        double maxScanDistanceSqr = maxScanDistance * maxScanDistance;
        Set<Integer> existingIds = new HashSet<>();
        for (Entity entity : entities) {
            existingIds.add(entity.getId());
        }
        // 遍历已加载实体（O(实体)）而非 ±maxScanDistance 大箱子 getEntitiesOfClass（O(箱子截面)）
        List<RVP_DecoyEntity> decoys = new ArrayList<>();
        for (Entity e : allEntities) {
            if (!(e instanceof RVP_DecoyEntity decoy) || !decoy.isAlive()
                    || decoy.rvp$decoyType() != RVP_EnumCountermeasureType.CHAFF) {
                continue;
            }
            Vec3 targetPos = decoy.getBoundingBox().getCenter();
            if (targetPos.distanceToSqr(radarPos) > maxScanDistanceSqr) {
                continue;
            }
            if (!isWithinScanHeight(radar, targetPos)) {
                continue;
            }
            Vec2 aimRot = radar.aimRot(targetPos);
            float yMin = radar.getYRotMin();
            float yMax = radar.getYRotMax();
            float y = normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!isYawWithin(y, yMin, yMax)) {
                continue;
            }
            if (Math.abs(aimRot.x - radar.getXRot()) > radar.getScanSectorAngle() / 2.0f) {
                continue;
            }
            decoys.add(decoy);
        }
        // 均匀分布采样：把 [0, maxScanDistance] 按距离分成 MAX_DISPLAYED_CHAFF 个区间，
        // 每个区间取离雷达最近的一枚——保证雷达界面在近/中/远都显示箔条，而不是取"最近的 N 枚"
        // （相近轮次的箔条挤在一起，取最近必然扎堆成一团）
        int bins = MAX_DISPLAYED_CHAFF;
        int added = 0;
        for (int b = 0; b < bins && added < bins; b++) {
            double binMin = maxScanDistance * b / bins;
            double binMax = maxScanDistance * (b + 1) / bins;
            RVP_DecoyEntity best = null;
            double bestDistSqr = Double.MAX_VALUE;
            for (RVP_DecoyEntity decoy : decoys) {
                if (existingIds.contains(decoy.getId())) {
                    continue;
                }
                double distSqr = decoy.getBoundingBox().getCenter().distanceToSqr(radarPos);
                double dist = Math.sqrt(distSqr);
                if (dist >= binMin && (b == bins - 1 ? dist <= binMax : dist < binMax)
                        && distSqr < bestDistSqr) {
                    best = decoy;
                    bestDistSqr = distSqr;
                }
            }
            if (best != null && existingIds.add(best.getId())) {
                entities.add(best);
                added++;
            }
        }
    }
}
