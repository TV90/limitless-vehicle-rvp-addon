package org.ywzj.rvp.server.light;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 真实光的恢复凭据。
 * @param position 放置位置
 * @param original 放置前的空气状态
 * @param placed 本系统实际放置的光状态
 * @param expires 到期世界 tick；从磁盘恢复时设为最早时间，下一次加载立即清理
 * @param created 首次创建世界 tick，限制连续开火时的最长占用
 */
public record RVP_DynamicBlockLightEntry(BlockPos position, BlockState original, BlockState placed, long expires, long created) {
    /** 只有当前位置仍等于本系统放置的状态才恢复；玩家的新方块优先。 */
    public boolean owns(BlockState current) { return placed.equals(current); }
}
