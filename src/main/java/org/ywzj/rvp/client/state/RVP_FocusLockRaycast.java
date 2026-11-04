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
}
