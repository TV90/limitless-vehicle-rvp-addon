package org.ywzj.rvp.targeting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端标记管理器。管理实体标记和方块标记的生命周期。
 * 实体标记通过 S2CTacticalRevealSnapshot 同步；方块标记通过 S2CMarkedBlockSync 同步。
 */
public final class RVP_MarkedTargetManager {

    /** IFF 类型常量 */
    public static final int IFF_FRIENDLY = 0;
    public static final int IFF_HOSTILE = 1;
    public static final int IFF_NEUTRAL = 2;

    /** 实体标记：entityId → (过期 gameTime, IFF 类型)（累加式，范围内所有实体均标记） */
    private static final Map<Integer, MarkedEntity> MARKED_ENTITIES = new ConcurrentHashMap<>();

    /** 方块标记：维度 → (坐标, 过期时间, 标记者名)（覆盖式，每维度仅保留最新一条） */
    private static final Map<ResourceLocation, MarkedBlock> MARKED_BLOCKS = new ConcurrentHashMap<>();

    private RVP_MarkedTargetManager() {}

    /** 标记实体（累加式：范围内所有实体均标记） */
    public static void markEntity(int entityId, long expireTick, int iffType) {
        MARKED_ENTITIES.put(entityId, new MarkedEntity(expireTick, iffType));
    }

    /** 标记方块（覆盖式：该维度仅保留最新一条） */
    public static void markBlock(ResourceLocation dimension, Vec3 pos, long expireTick, String markerName) {
        MARKED_BLOCKS.put(dimension, new MarkedBlock(pos, expireTick, markerName));
    }

    /**
     * 清理过期标记。
     *
     * @param level 当前维度
     * @return 方块标记是否被清理，用于触发客户端同步
     */
    public static boolean tick(ServerLevel level) {
        long gameTime = level.getGameTime();
        // 清理过期实体标记
        MARKED_ENTITIES.entrySet().removeIf(e -> e.getValue().expireTick <= gameTime);
        // 清理过期方块标记（每维度最多一条）
        boolean blockChanged = false;
        MarkedBlock block = MARKED_BLOCKS.get(level.dimension().location());
        if (block != null && block.expireTick <= gameTime) {
            MARKED_BLOCKS.remove(level.dimension().location());
            blockChanged = true;
        }
        return blockChanged;
    }

    public static List<MarkedEntityEntry> getMarkedEntityEntries() {
        List<MarkedEntityEntry> result = new ArrayList<>(MARKED_ENTITIES.size());
        MARKED_ENTITIES.forEach((id, me) -> result.add(new MarkedEntityEntry(id, me.iffType)));
        return result;
    }

    /** 获取指定维度的方块标记列表（兼容旧接口，最多返回一条） */
    public static List<MarkedBlock> getMarkedBlocks(ResourceLocation dimension) {
        MarkedBlock block = MARKED_BLOCKS.get(dimension);
        return block == null ? List.of() : List.of(block);
    }

    public static void clearAll() {
        MARKED_ENTITIES.clear();
        MARKED_BLOCKS.clear();
    }

    /** 实体标记条目（内部） */
    private record MarkedEntity(long expireTick, int iffType) {}

    /** 实体标记导出条目 */
    public record MarkedEntityEntry(int entityId, int iffType) {}

    /** 方块标记条目 */
    public record MarkedBlock(Vec3 pos, long expireTick, String markerName) {}
}
