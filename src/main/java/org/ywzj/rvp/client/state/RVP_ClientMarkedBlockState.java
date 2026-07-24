package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CMarkedBlockSync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端方块标记状态。按维度存储被吊舱标记的方块坐标及标记者。
 * 战术地图和世界渲染通过 {@link #getMarkedBlocks(ResourceLocation)} 获取标记点进行绘制。
 * <p>修复：使用维度→标记列表的映射，避免不同维度的同步包互相覆盖。</p>
 */
public final class RVP_ClientMarkedBlockState {

    private static final Map<ResourceLocation, List<S2CMarkedBlockSync.MarkedBlockEntry>> BLOCKS_BY_DIM = new ConcurrentHashMap<>();

    private RVP_ClientMarkedBlockState() {}

    public static void applySnapshot(S2CMarkedBlockSync msg) {
        BLOCKS_BY_DIM.put(msg.dimension, new ArrayList<>(msg.blocks));
    }

    public static List<S2CMarkedBlockSync.MarkedBlockEntry> getMarkedBlocks(@Nullable ResourceLocation currentDimension) {
        if (currentDimension == null) {
            return List.of();
        }
        List<S2CMarkedBlockSync.MarkedBlockEntry> result = BLOCKS_BY_DIM.get(currentDimension);
        return result != null ? result : List.of();
    }

    public static void clear() {
        BLOCKS_BY_DIM.clear();
    }
}
