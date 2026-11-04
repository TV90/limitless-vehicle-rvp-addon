package org.ywzj.rvp.client.compat.thermal;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import java.util.Optional;

/** 组合原管理器：资源与销毁仍归原实例，只有当前渲染线程能看见临时原版管线。 */
public final class RVP_ThermalPipelineManager extends PipelineManager {
    /** Oculus 原管理器，保留全部维度缓存与资源寿命。 */
    private final PipelineManager original;
    /** 标题界面预建的原版管线，不拥有光影 GPU 资源。 */
    private final WorldRenderingPipeline vanilla;
    /** 当前借用帧的线程、原管线与代号；后台区块任务始终读取原管线及颜色格式。 */
    private Thread thread;
    private WorldRenderingPipeline borrowed;
    private int serial;

    RVP_ThermalPipelineManager(PipelineManager original, WorldRenderingPipeline vanilla) {
        super(original::preparePipeline);
        this.original = original;
        this.vanilla = vanilla;
    }

    void begin(WorldRenderingPipeline pipeline) {
        thread = Thread.currentThread();
        borrowed = pipeline;
        serial = original.getVersionCounterForSodiumShaderReload();
    }

    void end() { thread = null; borrowed = null; }

    boolean isBorrowing() { return Thread.currentThread() == thread && getPipelineNullable() == vanilla; }

    WorldRenderingPipeline originalPipeline() { return original.getPipelineNullable(); }

    private WorldRenderingPipeline select(WorldRenderingPipeline real) {
        return Thread.currentThread() == thread && real == borrowed
                && original.getVersionCounterForSodiumShaderReload() == serial ? vanilla : real;
    }

    @Override
    public WorldRenderingPipeline preparePipeline(NamespacedId dimension) {
        // 调用原准备入口，维度切换与重载仍由 Oculus 处理。
        return select(original.preparePipeline(dimension));
    }

    @Override
    public WorldRenderingPipeline getPipelineNullable() { return select(original.getPipelineNullable()); }

    @Override
    public Optional<WorldRenderingPipeline> getPipeline() { return Optional.ofNullable(getPipelineNullable()); }

    @Override
    public int getVersionCounterForSodiumShaderReload() { return original.getVersionCounterForSodiumShaderReload(); }

    @Override
    public void destroyPipeline() {
        // 调用原销毁入口，热成像中重载也能完整释放全部 BSL/FBO，绝不隐藏资源所有者。
        original.destroyPipeline();
    }
}
