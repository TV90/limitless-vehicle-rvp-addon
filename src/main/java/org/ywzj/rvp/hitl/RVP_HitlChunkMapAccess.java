package org.ywzj.rvp.hitl;

import net.minecraft.server.level.ServerPlayer;

/** 按实体 ID 访问原版跟踪器；不会扫描整维度实体表。 */
public interface RVP_HitlChunkMapAccess {
    void rvp$updateObserver(int entityId, ServerPlayer player);
}
