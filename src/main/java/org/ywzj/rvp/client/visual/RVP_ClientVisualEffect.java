package org.ywzj.rvp.client.visual;

import net.minecraftforge.client.event.RenderLevelStageEvent;

/** 单个客户端视觉效果的最小生命周期接口。 */
public interface RVP_ClientVisualEffect {
    /** 推进一次客户端逻辑 tick。 */
    void tick();

    /** 把当前只读状态提交给世界渲染阶段，不在这里推进模拟。 */
    void render(RenderLevelStageEvent event);

    /**
     * 把当前只读状态重画进本体热成像 {@code thermal_buffer}（{@code RVP_ThermalParticleChannel}
     * 在 AFTER_PARTICLES 热成像窗口调用，调用时已绑定热缓冲为渲染目标、处于热成像激活态）。
     *
     * <p>默认空实现：粒子类特效（MCHR 烟/火光等）由热成像通道的粒子路径覆盖，重复画会双重
     * 提亮；自绘几何类特效（温压蘑菇云等，不经粒子引擎）必须 override 才能在热成像下显形
     * （本体 thermal.fsh 语义：没画进 thermal_buffer 的就是冷背景）。</p>
     */
    default void renderThermal(RenderLevelStageEvent event) {
    }

    /** 返回该实例是否已经结束。 */
    boolean isFinished();

    /** 释放该实例持有的客户端资源。 */
    void close();
}
