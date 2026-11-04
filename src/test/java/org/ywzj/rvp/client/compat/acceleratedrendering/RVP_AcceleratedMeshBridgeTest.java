package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.IAccelerationHolder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.IAcceleratedBufferSource;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.meshes.MeshUploads;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.renderer.RenderType;

import static org.junit.jupiter.api.Assertions.*;

class RVP_AcceleratedMeshBridgeTest {
    @Test
    void largeMeshChangesOnlySubmissionPolicyAndReusesView() {
        var views = new RVP_AcceleratedMeshBridge.DenseViews();
        ServerMesh original = mesh(156957, false);
        ServerMesh dense = views.select(original, 32768);
        assertTrue(dense.forceDense());
        assertFalse(original.forceDense(), "不得修改原始 SBM/AR mesh 描述");
        assertEquals(original.meshLayer(), dense.meshLayer());
        assertNotEquals(original.meshId(), dense.meshId(), "AR 提交缓存必须能区分两种策略");
        assertEquals(original.size(), dense.size());
        assertEquals(original.offset(), dense.offset());
        assertSame(original.meshBuffer(), dense.meshBuffer());
        assertSame(dense, views.select(original, 32768));
        assertSame(dense, views.select(dense, 32768));
    }

    @Test
    void thresholdAndLiveRollbackLeaveOriginalMeshAvailable() {
        var views = new RVP_AcceleratedMeshBridge.DenseViews();
        ServerMesh small = mesh(32767, false);
        ServerMesh boundary = mesh(32768, false);
        assertSame(small, views.select(small, 32768));
        assertTrue(views.select(boundary, 32768).forceDense());
        assertSame(boundary, views.select(boundary, 32769), "提高阈值后恢复原描述");
        ServerMesh alreadyDense = mesh(100, true);
        assertSame(alreadyDense, views.select(alreadyDense, 32768));
    }

    @Test
    void cameraMotionAndRecreatedEquivalentBuildersReuseGeometry() {
        var views = new RVP_AcceleratedMeshBridge.DenseViews();
        var delegate = new RecordingConsumer();
        var first = new RVP_AcceleratedMeshBridge.DenseConsumer(delegate, 32768, views);
        var nextDelegate = new RecordingConsumer(delegate.geometryKey);
        var next = new RVP_AcceleratedMeshBridge.DenseConsumer(nextDelegate, 65536, views);
        Map<Object, Object> sbmGeometryCache = new Object2ObjectOpenHashMap<>();
        Object geometry = new Object();
        sbmGeometryCache.put(first, geometry);
        Matrix4f cameraMotion = new Matrix4f().rotateY(0.3F).translate(3, 2, 1);
        next.beginTransform(cameraMotion, new Matrix3f());
        next.endTransform();
        assertSame(geometry, sbmGeometryCache.get(next));
        assertEquals(first.hashCode(), next.hashCode());
        assertEquals(cameraMotion, nextDelegate.transform);
        assertEquals(1, nextDelegate.endTransforms);
        assertNotSame(delegate, nextDelegate, "AR 环形缓冲会重建等价 builder");
        assertNotEquals(first, new RVP_AcceleratedMeshBridge.DenseConsumer(
                new RecordingConsumer(), 32768, views));
        assertNotEquals(first, delegate);
    }

    @Test
    void sbmRendererCallbackKeepsMeshInterceptionAndInstanceAttributes() {
        var delegate = new RecordingConsumer();
        var wrapped = new RVP_AcceleratedMeshBridge.DenseConsumer(
                delegate, 32768, new RVP_AcceleratedMeshBridge.DenseViews());
        var original = mesh(36819, false);
        Matrix4f transform = new Matrix4f().rotateX(0.4F);
        Matrix3f normal = new Matrix3f().rotateX(0.4F);
        int color = 0x7FA1B2C3;
        int light = 0x00F000A0;
        int overlay = 0x000A0001;
        wrapped.doRender((consumer, mesh, pose, normals, packedLight, packedOverlay, rgba) -> {
            assertSame(wrapped, consumer, "回调不能绕开本包装直接传底层 builder");
            IAcceleratedVertexConsumer accelerated = (IAcceleratedVertexConsumer) consumer;
            accelerated.beginTransform(pose, normals);
            mesh.write(accelerated, rgba, packedLight, packedOverlay);
            accelerated.endTransform();
        }, original, transform, normal, light, overlay, color);
        assertTrue(delegate.mesh.forceDense());
        assertEquals(original.size(), delegate.mesh.size());
        assertNotEquals(original.meshId(), delegate.mesh.meshId());
        assertEquals(color, delegate.color);
        assertEquals(light, delegate.light);
        assertEquals(overlay, delegate.overlay);
        assertEquals(transform, delegate.transform);
        assertEquals(normal, delegate.normal);
        assertEquals(1, delegate.endTransforms);
    }

    @Test
    void actualArUploadCacheKeepsIndependentPoliciesAcrossFrames() {
        var views = new RVP_AcceleratedMeshBridge.DenseViews();
        var uploads = new MeshUploads();
        var original = mesh(156957, false);
        var dense = views.select(original, 32768);
        assertFalse(uploads.get(original).getMesh().forceDense());
        uploads.clear();
        assertTrue(uploads.get(dense).getMesh().forceDense(), "不能被前一帧的 sparse 描述覆盖");
        assertFalse(uploads.get(original).getMesh().forceDense(), "关闭或其它通道仍保持原策略");
        uploads.clear();
        assertSame(dense, uploads.get(views.select(original, 32768)).getMesh());
    }

    @Test
    void ordinaryConsumerIsNeverForcedIntoAcceleratedPipeline() throws Exception {
        var original = new RecordingConsumer();
        assertSame(original, new RVP_AcceleratedMeshBridge().wrap(original, 32768));
        var wrapped = new RVP_AcceleratedMeshBridge.DenseConsumer(
                original, 32768, new RVP_AcceleratedMeshBridge.DenseViews());
        assertSame(original, wrapped.decorate(original));
    }

    @Test
    void worldBufferHolderReachesTheSameDensePathAsDirectPreview() throws Exception {
        var builder = builderWithoutGpu();
        var holder = new RecordingHolder(true, builder);
        var bridge = new RVP_AcceleratedMeshBridge();
        var direct = assertInstanceOf(RVP_AcceleratedMeshBridge.DenseConsumer.class,
                bridge.wrap(builder, 32768));
        var world = assertInstanceOf(RVP_AcceleratedMeshBridge.DenseConsumer.class,
                bridge.wrap(holder, 32768));
        assertSame(builder, direct.getDelegate());
        assertSame(builder, world.getDelegate(), "主世界包装器必须接到同一个底层 AR builder");
        assertEquals(1, holder.builderReads);
    }

    @Test
    void disabledOrUnavailableWorldAccelerationKeepsOriginalConsumer() throws Exception {
        var bridge = new RVP_AcceleratedMeshBridge();
        var disabled = new RecordingHolder(false, builderWithoutGpu());
        assertSame(disabled, bridge.wrap(disabled, 32768));
        assertEquals(0, disabled.builderReads, "不得为被 AR 过滤的批次强行初始化加速");
        var unavailable = new RecordingHolder(true, null);
        assertSame(unavailable, bridge.wrap(unavailable, 32768));
        assertEquals(1, unavailable.builderReads);
    }

    /** 只用于确认公开 holder 的转发身份，不创建或访问 OpenGL 缓冲。 */
    private static AcceleratedBufferBuilder builderWithoutGpu() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (AcceleratedBufferBuilder) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(AcceleratedBufferBuilder.class);
    }

    /** 模拟 AR 加到原版 / Sodium 缓冲上的公开 holder，不模拟其内部绘制实现。 */
    private static final class RecordingHolder extends RecordingConsumer implements IAccelerationHolder {
        /** 当前批次是否通过 AR 的过滤条件。 */
        private final boolean enabled;
        /** 当前批次实际的加速 builder；null 模拟不可用路径。 */
        private final AcceleratedBufferBuilder builder;
        /** 检查被过滤批次是否意外触发了加速初始化。 */
        private int builderReads;

        RecordingHolder(boolean enabled, AcceleratedBufferBuilder builder) {
            this.enabled = enabled;
            this.builder = builder;
        }
        @Override public boolean isAccelerated() { return enabled; }
        @Override public AcceleratedBufferBuilder getAccelerated() {
            builderReads++;
            return builder;
        }
        @Override public VertexConsumer initAcceleration(RenderType type, Supplier<IAcceleratedBufferSource> source) {
            throw new AssertionError("RVP 不得重新初始化 AR 批次");
        }
    }

    private static ServerMesh mesh(int size, boolean dense) {
        // 元数据测试不分配 OpenGL 缓冲；生产实现必须原样保留此引用。
        return new ServerMesh(3, ServerMesh.Builder.COUNTER++, size, 4096, dense, null);
    }

    /** 记录真实 AR 包装器调用的边界，不启动 OpenGL。 */
    private static class RecordingConsumer implements VertexConsumer, IAcceleratedVertexConsumer {
        /** 模拟 AR builder 按材质/布局定义的几何身份；批次对象本身可以不同。 */
        private final Object geometryKey;

        RecordingConsumer() { this(new Object()); }
        RecordingConsumer(Object geometryKey) { this.geometryKey = geometryKey; }
        @Override public boolean equals(Object other) {
            return other instanceof RecordingConsumer that && geometryKey.equals(that.geometryKey);
        }
        @Override public int hashCode() { return geometryKey.hashCode(); }

        /** 上次提交的 mesh 描述。 */
        private ServerMesh mesh;
        /** 上次提交的 RGBA、光照和覆盖层。 */
        private int color, light, overlay;
        /** 上次提交的相机/模型矩阵。 */
        private Matrix4f transform;
        /** 上次提交的法线矩阵。 */
        private Matrix3f normal;
        /** 完成变换的次数。 */
        private int endTransforms;

        @Override public void beginTransform(Matrix4f matrix, Matrix3f normals) {
            transform = new Matrix4f(matrix);
            normal = new Matrix3f(normals);
        }
        @Override public void endTransform() { endTransforms++; }
        @Override public void addServerMesh(ServerMesh submitted, int rgba, int packedLight, int packedOverlay) {
            mesh = submitted; color = rgba; light = packedLight; overlay = packedOverlay;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }
}
