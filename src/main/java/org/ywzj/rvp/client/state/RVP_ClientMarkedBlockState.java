package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CMarkedBlockSync;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端方块标记状态。存储当前维度内所有被吊舱标记的方块坐标。
 * 战术地图和炮兵地图通过 {@link #getMarkedBlocks(ResourceLocation)} 获取标记点进行绘制。
 */
public final class RVP_ClientMarkedBlockState {

    @Nullable
    private static ResourceLocation dimension;
    private static List<S2CMarkedBlockSync.MarkedBlockEntry> blocks = List.of();

    private RVP_ClientMarkedBlockState() {}

    public static void applySnapshot(S2CMarkedBlockSync msg) {
        dimension = msg.dimension;
        blocks = new ArrayList<>(msg.blocks);
    }

    public static List<S2CMarkedBlockSync.MarkedBlockEntry> getMarkedBlocks(@Nullable ResourceLocation currentDimension) {
        if (currentDimension == null || dimension == null || !dimension.equals(currentDimension)) {
            return List.of();
        }
        return blocks;
    }

    public static void clear() {
        dimension = null;
        blocks = List.of();
    }
}
