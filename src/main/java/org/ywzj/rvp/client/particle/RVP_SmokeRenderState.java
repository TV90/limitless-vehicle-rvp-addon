package org.ywzj.rvp.client.particle;

import org.ywzj.rvp.client.render.RVP_GlStateSync;

/** 烟雾共用的深度约定：真实方块/实体仍能挡住烟，烟自身不能进入光照遮挡深度。 */
public final class RVP_SmokeRenderState {
    private RVP_SmokeRenderState() { }

    /**
     * 每个烟雾批次开始时应用；不依赖该批次在原版还是 Oculus 的提前粒子窗口绘制。
     * Oculus 的 BEFORE/MIXED 路径可能先画自定义烟、后画实体，仅提前复制场景深度仍会读到烟。
     */
    public static void applyDepth() {
        // 调用双向状态同步，墙体继续遮住烟，同时纠正其它渲染通道留下的真实 GL/引擎缓存差异。
        RVP_GlStateSync.depthTest(true);
        // 调用强制禁写，烟即使早于深度采集也只写颜色，不给方块、实体或烟自身制造假阴影。
        RVP_GlStateSync.depthMask(false);
    }
}
