package org.ywzj.rvp.util;

/**
 * 服务端下发的每玩家载具显示边界；DH、远距代理与真实载具共用。
 *
 * @param allowBeyondViewDistance 服务端与客户端均已允许超视距显示；未公开的单人世界豁免服务端默认禁用
 * @param viewDistanceChunks 两端实际视距的较小值，单位区块，合法范围 2..32
 */
public record RVP_RemoteVehicleViewPolicy(boolean allowBeyondViewDistance, int viewDistanceChunks) {
    /** 首包前只允许客户端视距内显示，不授予远距扩展能力。 */
    public static final RVP_RemoteVehicleViewPolicy DEFAULT = new RVP_RemoteVehicleViewPolicy(false, 32);

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

    /** 服务端在授权和数量预算前收紧发送范围；被排除目标不会进入视觉租约并集。 */
    public double maximumDistance(double configuredDistance) {
        return allowBeyondViewDistance ? configuredDistance
                : Math.min(configuredDistance, viewDistanceChunks * 16.0D);
    }

    /** 客户端调低视距或关闭扩展时立即收紧，无需等待下一份服务端快照。 */
    public boolean allowsDistance(double horizontalDistanceSquared, int clientChunks, boolean clientRequestsBeyond) {
        if (!Double.isFinite(horizontalDistanceSquared) || horizontalDistanceSquared < 0.0D) {
            return false;
        }
        double range = Math.min(viewDistanceChunks, clampChunks(clientChunks)) * 16.0D;
        return allowBeyondViewDistance && clientRequestsBeyond
                || horizontalDistanceSquared <= range * range;
    }

    /** 与原版区块管理和已有视距上报保持同一上下限。 */
    private static int clampChunks(int chunks) {
        return Math.max(2, Math.min(32, chunks));
    }
}
