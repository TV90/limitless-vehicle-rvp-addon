package org.ywzj.rvp.client.render;

import net.minecraft.client.renderer.MultiBufferSource;
import org.ywzj.rvp.client.compat.acceleratedrendering.RVP_AcceleratedMeshCompat;

/** 仅命中展板显式开启的即时绘制窗口；不影响世界、手持物和其它界面。 */
public final class RVP_HitPanelMesh {
    /** 当前展板的原始缓冲；身份不符的包装器继续走原路径，避免绕过额外顶点语义。 */
    private static MultiBufferSource.BufferSource active;

    private RVP_HitPanelMesh() { }

    public static Scope open(MultiBufferSource.BufferSource source) {
        // 先冲刷已排队的背景/文字，再允许共享不透明几何即时写入深度。
        source.endBatch();
        Scope scope = new Scope(active, source);
        active = source;
        return scope;
    }

    public static boolean accepts(MultiBufferSource source) {
        // 调用可选兼容层，只额外接受内部仍指向本缓冲的 AR 透明转发记录。
        return active != null && (source == active || RVP_AcceleratedMeshCompat.isPanelSource(source, active));
    }

    /** 在即时 mesh 前冲刷本窗口缓冲；调用方不拆解消费者或第三方包装。 */
    public static void flush() {
        if (active != null) active.endBatch();
    }

    public static final class Scope implements AutoCloseable {
        /** 嵌套窗口之前的缓冲，异常退出也恢复。 */
        private final MultiBufferSource.BufferSource previous;
        /** 当前窗口的缓冲，关闭前完成透明特殊骨和命中动画提交。 */
        private final MultiBufferSource.BufferSource source;

        private Scope(MultiBufferSource.BufferSource previous, MultiBufferSource.BufferSource source) {
            this.previous = previous;
            this.source = source;
        }

        @Override
        public void close() {
            try { source.endBatch(); }
            finally { active = previous; }
        }
    }
}
