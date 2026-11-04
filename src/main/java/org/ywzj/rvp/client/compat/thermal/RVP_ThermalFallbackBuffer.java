package org.ywzj.rvp.client.compat.thermal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.vertices.ExtendingBufferBuilder;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/** 暂停帧的小网格直接使用 Iris 公开的原版格式入口，不改全局地形格式。 */
final class RVP_ThermalFallbackBuffer {
    /** 仅本次主体不透明网格的临时顶点，不绑定 AR 世界批次、不跨帧保留。 */
    private final BufferBuilder builder = new BufferBuilder(65536);
    /** 当前实际材质；切换时先提交上一材质，与原 BufferSource 顺序一致。 */
    private RenderType current;
    /** 只暴露消费者入口，避免其它模组把自定义 builder 替换为默认缓冲。 */
    private final MultiBufferSource source = this::buffer;

    private VertexConsumer buffer(RenderType material) {
        if (current != material) {
            flush();
            // 调用 Iris 既有公开入口，仅本 builder 不扩展；后台区块与缓存的地形格式保持原状态。
            ((ExtendingBufferBuilder) (Object) builder).iris$beginWithoutExtending(material.mode(), material.format());
            current = material;
        }
        return builder;
    }

    private void flush() {
        RenderType material = current;
        current = null;
        if (material == null) return;
        boolean extended = ImmediateState.renderWithExtendedVertexFormat;
        ImmediateState.renderWithExtendedVertexFormat = false;
        try {
            // 调用原 RenderType 提交，保留深度、剔除、贴图与排序语义。
            material.end(builder, RenderSystem.getVertexSorting());
        } finally { ImmediateState.renderWithExtendedVertexFormat = extended; }
    }

    RVP_ThermalShaderPause.Fallback open() {
        return new RVP_ThermalShaderPause.Fallback() {
            @Override public MultiBufferSource source() { return source; }
            @Override public void close() { flush(); }
        };
    }
}
