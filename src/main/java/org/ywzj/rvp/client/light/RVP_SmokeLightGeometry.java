package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 记录烟雾与炮口贴图的实际位置、UV、颜色和透明度；只加光，不重新生成原几何。 */
public final class RVP_SmokeLightGeometry {
    /** 每顶点：位置三项、UV 两项、RGBA 四项。 */
    static final int STRIDE = 9;
    /** 有界帧内缓存，超过三万余面片时不无限增长。 */
    static final int MAX_VERTICES = 32768 * 4;
    /** 按实际贴图与坐标空间分组，防止同一贴图的粒子和模型顶点混用矩阵。 */
    private final Map<Long, Batch> batches = new LinkedHashMap<>();
    /** 当前帧总顶点数。 */
    private int vertices;

    /** 每个显示帧清空有效长度，复用已有数组。 */
    public void beginFrame() { vertices = 0; batches.values().forEach(batch -> batch.size = 0); }

    /** 世界或资源重载时丢弃旧贴图编号及缓存。 */
    public void clear() { batches.clear(); vertices = 0; }

    /** 包装正常绘制出口，原顶点原样转发，同时记录同一份烟雾几何。delegate 可空，用于粒子重取顶点。 */
    public VertexConsumer capture(VertexConsumer delegate, int texture) {
        // 调用共用记录入口；粒子和自绘云提供相机相对的世界坐标。
        return capture(delegate, texture, false);
    }

    /** 模型顶点已包含观察变换；单独分组但与烟雾共用同一帧数量上限。 */
    public VertexConsumer capture(VertexConsumer delegate, int texture, boolean viewSpace) {
        if (texture <= 0) { return delegate; }
        long key = ((long) texture << 1) | (viewSpace ? 1L : 0L);
        Batch batch = batches.computeIfAbsent(key, ignored -> new Batch(texture, viewSpace));
        return new Capture(delegate, batch);
    }

    /** 帧内贴图分组只读遍历入口。 */
    Collection<Batch> batches() { return batches.values(); }
    /** 供验证与状态诊断查看记录量。 */
    public int vertexCount() { return vertices; }

    /** 顶点数据批；使用数组而非逐顶点分配对象，避免烟云密集时产生大量垃圾。 */
    static final class Batch {
        /** 真实贴图编号。 */
        final int texture;
        /** true 为已经过观察变换的模型坐标，false 为相机相对的粒子世界坐标。 */
        final boolean viewSpace;
        /** 交错顶点数据。 */
        float[] data = new float[STRIDE * 256];
        /** 有效浮点项数，只有完整四边形参与绘制。 */
        int size;
        Batch(int texture, boolean viewSpace) { this.texture = texture; this.viewSpace = viewSpace; }

        /** 面片包围球与任一光球相交时才提交；不改变 Shader 的真实照明半径。 */
        boolean intersects(int offset, List<RVP_ScreenLight> lights, Vec3 camera) {
            // 调用同一包围球检查，普通粒子不需要观察空间光球副本。
            return intersects(offset, lights, camera, null);
        }

        /** viewSpheres 非空时使用已上传的观察空间光球，避免旋转相机后误剔除模型炮口。 */
        boolean intersects(int offset, List<RVP_ScreenLight> lights, Vec3 camera, float[] viewSpheres) {
            double x = 0, y = 0, z = 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                int at = offset + vertex * STRIDE;
                x += data[at] * 0.25; y += data[at + 1] * 0.25; z += data[at + 2] * 0.25;
            }
            double radiusSq = 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                int at = offset + vertex * STRIDE;
                double dx = data[at] - x, dy = data[at + 1] - y, dz = data[at + 2] - z;
                radiusSq = Math.max(radiusSq, dx * dx + dy * dy + dz * dz);
            }
            double radius = Math.sqrt(radiusSq);
            for (int index = 0; index < lights.size(); index++) {
                RVP_ScreenLight light = lights.get(index);
                double dx = (viewSpheres == null ? light.position().x - camera.x : viewSpheres[index * 4]) - x;
                double dy = (viewSpheres == null ? light.position().y - camera.y : viewSpheres[index * 4 + 1]) - y;
                double dz = (viewSpheres == null ? light.position().z - camera.z : viewSpheres[index * 4 + 2]) - z;
                double reach = radius + light.radius();
                if (dx * dx + dy * dy + dz * dz < reach * reach) { return true; }
            }
            return false;
        }
    }

    /** 保持原 VertexConsumer 链式调用语义；光照副本只记录常用顶点属性。 */
    private final class Capture implements VertexConsumer {
        /** 原正常绘制出口；空表示仅取几何。 */
        private final VertexConsumer delegate;
        /** 当前贴图批。 */
        private final Batch batch;
        /** 当前顶点属性，默认颜色为白色不透明。 */
        private final float[] current = {0, 0, 0, 0, 0, 1, 1, 1, 1};

        Capture(VertexConsumer delegate, Batch batch) { this.delegate = delegate; this.batch = batch; }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            if (delegate != null) { delegate.vertex(x, y, z); }
            current[0] = (float) x; current[1] = (float) y; current[2] = (float) z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            if (delegate != null) { delegate.color(r, g, b, a); }
            current[5] = r / 255F; current[6] = g / 255F; current[7] = b / 255F; current[8] = a / 255F; return this;
        }
        @Override public VertexConsumer uv(float u, float v) {
            if (delegate != null) { delegate.uv(u, v); }
            current[3] = u; current[4] = v; return this;
        }
        @Override public VertexConsumer overlayCoords(int u, int v) {
            if (delegate != null) { delegate.overlayCoords(u, v); } return this;
        }
        @Override public VertexConsumer uv2(int u, int v) {
            if (delegate != null) { delegate.uv2(u, v); } return this;
        }
        @Override public VertexConsumer normal(float x, float y, float z) {
            if (delegate != null) { delegate.normal(x, y, z); } return this;
        }
        @Override public void endVertex() {
            if (delegate != null) { delegate.endVertex(); }
            if (vertices >= MAX_VERTICES) { return; }
            if (batch.size + STRIDE > batch.data.length) {
                batch.data = Arrays.copyOf(batch.data, Math.min(MAX_VERTICES * STRIDE, batch.data.length * 2));
            }
            boolean finite = true;
            for (float value : current) { finite &= Float.isFinite(value); }
            if (finite) { System.arraycopy(current, 0, batch.data, batch.size, STRIDE); }
            else { Arrays.fill(batch.data, batch.size, batch.size + STRIDE, 0); }
            batch.size += STRIDE; vertices++;
        }
        @Override public void defaultColor(int r, int g, int b, int a) {
            if (delegate != null) { delegate.defaultColor(r, g, b, a); }
            current[5] = r / 255F; current[6] = g / 255F; current[7] = b / 255F; current[8] = a / 255F;
        }
        @Override public void unsetDefaultColor() { if (delegate != null) { delegate.unsetDefaultColor(); } }
    }
}
