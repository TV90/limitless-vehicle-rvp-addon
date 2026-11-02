package org.ywzj.rvp.hitl;

import net.minecraft.server.level.ServerPlayer;

/** 原版实体跟踪器的最小调用端口，重新评估一个观察者，不创建第二套实体副本。 */
public interface RVP_HitlTrackedEntityAccess {
    void rvp$updateObserver(ServerPlayer player);
}
