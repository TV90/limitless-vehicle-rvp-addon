package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.github.argon4w.acceleratedrendering.core.backends.buffers.IServerBuffer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.IAccelerationHolder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedVertexConsumerWrapper;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/** 复用 AR 1.0.14 公开消费者接口；不复制几何、不改写 SBM 缓存、不接管 GPU 缓冲寿命。 */
public final class RVP_AcceleratedMeshBridge implements RVP_AcceleratedMeshCompat.ConsumerWrapper {
    /** 源 mesh 的弱引用缓存；几何重载后旧 mesh 可随 AR/SBM 原有生命周期释放。 */
    private final DenseViews denseViews = new DenseViews();

    /** 在任何绘制前检查构造签名，避免不兼容 AR 版本到实际提交时才触发链接错误。 */
    public RVP_AcceleratedMeshBridge() throws ReflectiveOperationException {
        ServerMesh.class.getConstructor(int.class, int.class, int.class, int.class,
                boolean.class, IServerBuffer.class);
        var counter = ServerMesh.Builder.class.getField("COUNTER");
        if (counter.getType() != int.class || !Modifier.isStatic(counter.getModifiers())
                || Modifier.isFinal(counter.getModifiers())) {
            throw new NoSuchFieldException("AR mesh ID allocator changed");
        }
        AcceleratedVertexConsumerWrapper.class.getMethod("addServerMesh", ServerMesh.class,
                int.class, int.class, int.class);
        if (IAccelerationHolder.class.getMethod("getAccelerated").getReturnType()
                != AcceleratedBufferBuilder.class) {
            throw new NoSuchMethodException("AR acceleration holder changed");
        }
    }

    @Override
    public VertexConsumer wrap(VertexConsumer consumer, int minVertices) {
        AcceleratedBufferBuilder builder = null;
        if (consumer instanceof AcceleratedBufferBuilder direct) {
            builder = direct;
        } else if (consumer instanceof IAccelerationHolder holder
                && consumer instanceof IAcceleratedVertexConsumer accelerated
                && accelerated.isAccelerated()) {
            // 主世界的 BufferBuilder / SodiumBufferBuilder 持有真正的 AR builder；
            // SBM 也通过此公开入口提交。只识别 raw builder 会让优化仅在直连预览中生效。
            // 先检查该批次是否获准加速，避免绕过 AR 过滤器或把原版批次强行改道。
            builder = holder.getAccelerated();
        }
        // 不拆解普通 AR 包装链：decal、outline、UV 修饰和多路消费仍由原通道处理。
        return builder == null ? consumer : new DenseConsumer(builder, minVertices, denseViews);
    }

    /** 为同一 GPU 几何建立独立提交身份；偏移、顶点数和缓冲地址保持一致。 */
    static final class DenseViews {
        /** 值不持有源 mesh 本身，避免弱键被值反向保活；只在渲染线程使用。 */
        private final Map<ServerMesh, ServerMesh> views = new WeakHashMap<>();

        ServerMesh select(ServerMesh mesh, int minVertices) {
            if (mesh.forceDense() || mesh.size() < minVertices) {
                return mesh;
            }
            // AR 的 MeshUploads 按 ID 保留首个描述；复用旧 ID 会让开关失效，或污染原通道策略。
            // 沿用 AR Builder 的公开 ID 分配器，仅分配描述身份，不分配/复制 GPU 几何。
            return views.computeIfAbsent(mesh, original -> new ServerMesh(
                    original.meshLayer(), ServerMesh.Builder.COUNTER++, original.size(), original.offset(),
                    true, original.meshBuffer()));
        }
    }

    /** 沿用 AR 自带的变换、颜色、光照与几何转发，只在 mesh 提交边界选择 dense 描述。 */
    static final class DenseConsumer extends AcceleratedVertexConsumerWrapper {
        /** 当前真实 AR 消费者；其 equals/hashCode 按 RenderType 与布局定义几何身份。 */
        private final VertexConsumer delegate;
        /** 本次调用的顶点阈值；不改变几何，故不参与缓存键。 */
        private final int minVertices;
        /** 共享提交视图，避免每帧为相同 mesh 重建描述。 */
        private final DenseViews denseViews;

        DenseConsumer(VertexConsumer delegate, int minVertices, DenseViews denseViews) {
            this.delegate = delegate;
            this.minVertices = minVertices;
            this.denseViews = denseViews;
        }

        @Override
        protected VertexConsumer getDelegate() {
            return delegate;
        }

        @Override
        public VertexConsumer decorate(VertexConsumer buffer) {
            // 本包装仅接受原始 AcceleratedBufferBuilder，其 decorate 本身也是恒等操作。
            return buffer;
        }

        @Override
        public void addServerMesh(ServerMesh mesh, int color, int light, int overlay) {
            // 调用共享描述选择器；复用 AR 父类提交，保持同一变换、颜色、光照和覆盖层。
            super.addServerMesh(denseViews.select(mesh, minVertices), color, light, overlay);
        }

        @Override
        public boolean equals(Object other) {
            // AR 每批会重建 builder；必须沿用其材质/布局相等语义，不能比较对象地址。
            return this == other || other instanceof DenseConsumer that && delegate.equals(that.delegate);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }
}
