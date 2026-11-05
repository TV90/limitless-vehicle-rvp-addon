package org.ywzj.rvp.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.DoubleSupplier;

/**
 * 服务端下发的每玩家载具显示边界；DH、远距代理与真实载具共用。
 *
 * @param allowBeyondViewDistance 服务端与客户端均已允许超视距显示；未公开的单人世界豁免服务端默认禁用
 * @param viewDistanceChunks 两端实际视距的较小值，单位区块，合法范围 2..32
 */
public record RVP_RemoteVehicleViewPolicy(boolean allowBeyondViewDistance, int viewDistanceChunks) {
    /** 首包前使用保守的低空显示策略。 */
    public static final RVP_RemoteVehicleViewPolicy DEFAULT = new RVP_RemoteVehicleViewPolicy(false, 32);

    /** 默认多人限制隐藏的最大离地高度，单位格，包含边界。 */
    private static final double LOW_VEHICLE_HEIGHT = 25.0D;

    /** 拒绝非法协议范围，避免损坏快照变成无上限显示。 */
    public RVP_RemoteVehicleViewPolicy {
        if (viewDistanceChunks < 2 || viewDistanceChunks > 32) {
            throw new IllegalArgumentException("Vehicle view distance must be within 2..32 chunks");
        }
    }

    /** 仅未公开的内置单人服免于服务端钳制；开放 LAN 后立即使用多人服规则。 */
    public static RVP_RemoteVehicleViewPolicy forConnection(
            boolean dedicatedServer, boolean published, boolean serverAllowsBeyond,
            boolean clientRequestsBeyond, int serverChunks, int clientChunks) {
        boolean singleplayer = !dedicatedServer && !published;
        int chunks = Math.min(clampChunks(serverChunks), clampChunks(clientChunks));
        return new RVP_RemoteVehicleViewPolicy(
                clientRequestsBeyond && (singleplayer || serverAllowsBeyond), chunks);
    }

    /** 两端共用边界；仅在视距外且需要判断低空限制时才读取离地高度。 */
    public boolean allowsDistance(double horizontalDistanceSquared, int clientChunks,
                                  boolean clientRequestsBeyond, DoubleSupplier heightAboveGround) {
        if (!Double.isFinite(horizontalDistanceSquared) || horizontalDistanceSquared < 0.0D) {
            return false;
        }
        double range = Math.min(viewDistanceChunks, clampChunks(clientChunks)) * 16.0D;
        if (horizontalDistanceSquared <= range * range) {
            return true;
        }
        if (!clientRequestsBeyond) {
            return false;
        }
        if (allowBeyondViewDistance) {
            return true;
        }
        double height = heightAboveGround.getAsDouble();
        return Double.isFinite(height) && height > LOW_VEHICLE_HEIGHT;
    }

    /** 从已加载的地表高度图测量载具离地高度；缺地形时返回未知，不加载区块。 */
    public static double heightAboveGround(Entity vehicle) {
        var level = vehicle.level();
        var pos = vehicle.blockPosition();
        if (!level.hasChunkAt(pos)) {
            return Double.NaN;
        }
        return Math.max(0.0D, vehicle.getY()
                - level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
    }

    /** 与原版区块管理和已有视距上报保持同一上下限。 */
    private static int clampChunks(int chunks) {
        return Math.max(2, Math.min(32, chunks));
    }
}
