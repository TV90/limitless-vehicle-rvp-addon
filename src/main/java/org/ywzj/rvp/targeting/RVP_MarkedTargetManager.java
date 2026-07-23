package org.ywzj.rvp.targeting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端标记管理器。管理实体标记和方块标记的生命周期。
 * 实体标记通过 S2CTacticalRevealSnapshot 同步；方块标记通过 S2CMarkedBlockSync 同步。
 */
public final class RVP_MarkedTargetManager {

    /** 实体标记：entityId → 过期 gameTime */
    private static final Map<Integer, Long> MARKED_ENTITIES = new ConcurrentHashMap<>();

    /** 方块标记：维度 → [(坐标, 过期时间)] */
    private static final Map<ResourceLocation, List<MarkedBlock>> MARKED_BLOCKS = new ConcurrentHashMap<>();

    private RVP_MarkedTargetManager() {}

    public static void markEntity(int entityId, long expireTick) {
        MARKED_ENTITIES.put(entityId, expireTick);
    }

    public static void markBlock(ResourceLocation dimension, Vec3 pos, long expireTick) {
        MARKED_BLOCKS.computeIfAbsent(dimension, k -> new ArrayList<>())
                .add(new MarkedBlock(pos, expireTick));
    }

    /**
     * 清理过期标记。
     *
     * @param level 当前维度
     * @return 该维度方块标记是否发生变化（有条目被移除），用于触发客户端同步
     */
    public static boolean tick(ServerLevel level) {
        long gameTime = level.getGameTime();
        // 清理过期实体标记（全局，按当前维度 gameTime）
        MARKED_ENTITIES.entrySet().removeIf(e -> e.getValue() <= gameTime);
        // 清理过期方块标记（按维度）
        boolean blockChanged = false;
        List<MarkedBlock> blocks = MARKED_BLOCKS.get(level.dimension().location());
        if (blocks != null) {
            Iterator<MarkedBlock> it = blocks.iterator();
            while (it.hasNext()) {
                if (it.next().expireTick <= gameTime) {
                    it.remove();
                    blockChanged = true;
                }
            }
            if (blocks.isEmpty()) {
                MARKED_BLOCKS.remove(level.dimension().location());
            }
        }
        return blockChanged;
    }

    public static List<Integer> getMarkedEntityIds() {
        return new ArrayList<>(MARKED_ENTITIES.keySet());
    }

    public static List<MarkedBlock> getMarkedBlocks(ResourceLocation dimension) {
        List<MarkedBlock> blocks = MARKED_BLOCKS.get(dimension);
        return blocks == null ? List.of() : new ArrayList<>(blocks);
    }

    public static void clearAll() {
        MARKED_ENTITIES.clear();
        MARKED_BLOCKS.clear();
    }

    /** 方块标记条目 */
    public record MarkedBlock(Vec3 pos, long expireTick) {}
}
