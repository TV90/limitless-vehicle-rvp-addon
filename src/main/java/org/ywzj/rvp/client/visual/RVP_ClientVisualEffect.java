package org.ywzj.rvp.client.visual;

import net.minecraftforge.client.event.RenderLevelStageEvent;

/** 单个客户端视觉效果的最小生命周期接口。 */
public interface RVP_ClientVisualEffect {
    /** 推进一次客户端逻辑 tick。 */
    void tick();

    /** 把当前只读状态提交给世界渲染阶段，不在这里推进模拟。 */
    void render(RenderLevelStageEvent event);

    /** 返回该实例是否已经结束。 */
    boolean isFinished();

    /** 释放该实例持有的客户端资源。 */
    void close();
}
