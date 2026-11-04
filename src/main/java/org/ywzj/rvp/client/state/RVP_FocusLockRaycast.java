package org.ywzj.rvp.client.state;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.api.entity.TargetObstruction;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 焦点专用选点，显式保留未命中语义，不把射线末端伪装成地面。 */
public final class RVP_FocusLockRaycast {
    /** 普通已加载方块/实体射线的最大长度，单位为方块，不受画面视距设置截断。 */
    private static final double LOADED_DISTANCE = 1024.0D;
    /** 可选远景地形查询的最大长度，单位为方块，与对照项目远景查询范围一致。 */
    public static final int TERRAIN_DISTANCE = 48_000;
    /** 48 千米射线最多穿过约 67884 根水平地形柱；留出边界余量并限制单次工作量。 */
    private static final int MAX_TERRAIN_COLUMNS = 70_000;
    /** 求交距离的浮点边界容差，单位为方块，不用于扩大地形受击范围。 */
    private static final double TERRAIN_EPSILON = 1.0E-7D;

    /** 工具类不允许实例化。 */
    private RVP_FocusLockRaycast() {}

    /** 返回最近的已加载真实表面；无命中返回 null，绝不返回临时远端点。 */
    @Nullable
    public static Vec3 loadedSurface(WeaponUnit station, Vec3 origin, Vec3 direction) {
        // 调用坐标检查，拒绝初始化或世界卸载期间的无效射线。
        if (!RVP_FocusLockAim.isFinite(origin) || !RVP_FocusLockAim.isFinite(direction)
                || direction.lengthSqr() < 1.0E-8D) {
            return null;
        }
        Vec3 end = origin.add(direction.normalize().scale(LOADED_DISTANCE));
        // 调用本体载具公开入口取得射线所属世界；原版客户端不会因此加载远处区块。
        var vehicle = station.getVehicle();
        BlockHitResult block = vehicle.level().clip(new ClipContext(origin, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        Vec3 nearest = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;
        // 调用本体实体射线，保留载具自身/乘员排除与实体受击盒处理。
        EntityHitResult entity = VectorUtil.hitEntity(vehicle, origin, end);
        if (entity != null && !(entity.getEntity() instanceof SightObstruction)
                && !(entity.getEntity() instanceof TargetObstruction)
                && (nearest == null || origin.distanceToSqr(entity.getLocation()) < origin.distanceToSqr(nearest))) {
            nearest = entity.getLocation();
        }
        return nearest;
    }

    /**
     * 从近到远遍历视线穿过的水平地形柱，返回最近的真实表面。
     *
     * <p>先裁掉世界高度范围外的空中路段，因此高于建筑上限的相机仍能向下选点。
     * 使用逐格边界步进（DDA），即每次走到下一根柱的边界，不做固定间隔抽样；
     * 数据缺失只跳过当前柱，不终止整条视线。所有距离单位为方块。</p>
     */
    @Nullable
    public static Vec3 terrainSurface(Vec3 origin, Vec3 direction, int maxDistance,
                                      int minY, int maxY, TerrainColumnRaycast columns) {
        // 调用有限性检查，拒绝无效射线，且避免水平格坐标转换溢出。
        if (!RVP_FocusLockAim.isFinite(origin) || !RVP_FocusLockAim.isFinite(direction)
                || !Double.isFinite(direction.lengthSqr()) || direction.lengthSqr() < 1.0E-8D
                || maxDistance <= 0 || minY >= maxY
                || Math.abs(origin.x) + TERRAIN_DISTANCE >= Integer.MAX_VALUE
                || Math.abs(origin.z) + TERRAIN_DISTANCE >= Integer.MAX_VALUE) {
            return null;
        }
        Vec3 unit = direction.normalize();
        double startDistance = 0.0D;
        double endDistance = Math.min(maxDistance, TERRAIN_DISTANCE);
        if (unit.y == 0.0D) {
            if (origin.y < minY || origin.y >= maxY) return null;
        } else {
            double minCrossing = (minY - origin.y) / unit.y;
            double maxCrossing = (maxY - origin.y) / unit.y;
            startDistance = Math.max(startDistance, Math.min(minCrossing, maxCrossing));
            endDistance = Math.min(endDistance, Math.max(minCrossing, maxCrossing));
            if (startDistance > endDistance) return null;
        }

        Vec3 start = origin.add(unit.scale(startDistance));
        int x = (int) Math.floor(start.x);
        int z = (int) Math.floor(start.z);
        int stepX = unit.x > 0.0D ? 1 : unit.x < 0.0D ? -1 : 0;
        int stepZ = unit.z > 0.0D ? 1 : unit.z < 0.0D ? -1 : 0;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / unit.x);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / unit.z);
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY
                : (x + (stepX > 0 ? 1.0D : 0.0D) - origin.x) / unit.x;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : (z + (stepZ > 0 ? 1.0D : 0.0D) - origin.z) / unit.z;
        Vec3 nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int visited = 0; visited < MAX_TERRAIN_COLUMNS; visited++) {
            if (Thread.currentThread().isInterrupted()) return null;
            // 调用可选地形数据提供方，只读当前柱；空结果不会阻断后方已有的远景数据。
            Vec3 hit = columns.intersect(x, z);
            if (RVP_FocusLockAim.isFinite(hit)) {
                double distance = hit.subtract(origin).dot(unit);
                if (distance > TERRAIN_EPSILON && distance >= startDistance - TERRAIN_EPSILON
                        && distance <= endDistance + TERRAIN_EPSILON && distance < nearestDistance) {
                    nearest = hit;
                    nearestDistance = distance;
                }
            }
            double columnEnd = Math.min(endDistance, Math.min(nextX, nextZ));
            // 粗粒度柱可能跨多格；走到候选命中前仍检查中间的更近表面，不能提前穿透。
            if (nearest != null && nearestDistance <= columnEnd + TERRAIN_EPSILON) return nearest;
            if (columnEnd >= endDistance) return null;
            boolean crossX = nextX <= nextZ;
            boolean crossZ = nextZ <= nextX;
            if (crossX) {
                x += stepX;
                nextX += deltaX;
            }
            if (crossZ) {
                z += stepZ;
                nextZ += deltaZ;
            }
        }
        return null;
    }

    /** 不引用远景模组类型的单柱求交接口；实现方返回该柱与原始视线的最近真实交点。 */
    @FunctionalInterface
    public interface TerrainColumnRaycast {
        /** 只查询给定水平格坐标，缺失数据或没有真实表面时返回 null。 */
        @Nullable Vec3 intersect(int x, int z);
    }
}
