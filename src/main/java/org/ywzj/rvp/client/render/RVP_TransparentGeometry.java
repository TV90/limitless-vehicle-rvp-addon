package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** 透明面帧内记录器：保存已经过骨骼/观察变换的顶点，三角形与四边形共用远近排序。 */
final class RVP_TransparentGeometry<M> {
    /** 每顶点：位置 3、颜色 4、UV 2、overlay 2、light 2、法线 3。颜色保留原 8 位值。 */
    private static final int STRIDE = 16;
    /** 每面预留四个顶点；三角形只使用前三个，避免每面创建对象。 */
    private static final int FACE_STRIDE = STRIDE * 4;
    /** 可复用交错顶点数据，按需扩容。 */
    private float[] data = new float[512 * FACE_STRIDE];
    /** 每面的材质，与排序索引分离。 */
    private Object[] materials = new Object[512];
    /** 每面实际顶点数，3 或 4。 */
    private byte[] sizes = new byte[512];
    /** 观察空间面心到相机的距离平方。 */
    private float[] distances = new float[512];
    /** 只排序整数索引，不搬运顶点。 */
    private int[] order = new int[512];
    /** 每种材质复用一个顶点接收器。 */
    private final Map<M, Capture> captures = new HashMap<>();
    /** 当前帧完整面数；不完整面不提交。 */
    private int faces;

    /** 清有效长度并释放帧内材质引用，数组继续复用。 */
    void beginFrame() {
        Arrays.fill(materials, 0, faces, null);
        faces = 0;
        captures.values().forEach(capture -> capture.pending = 0);
    }

    /** 创建/复用对应材质的完整面接收器；不触发实际绘制或 GL 状态改变。 */
    VertexConsumer capture(M material, int verticesPerFace) {
        if (verticesPerFace != 3 && verticesPerFace != 4) throw new IllegalArgumentException("只接受三角形或四边形");
        Capture result = captures.computeIfAbsent(material, key -> new Capture(key, verticesPerFace));
        if (result.verticesPerFace != verticesPerFace) throw new IllegalArgumentException("同一材质的图元类型不能改变");
        return result;
    }

    int faceCount() { return faces; }

    /** 距离相同保留提交次序，避免静止相机下出现随机翻面；适用于 TRIANGLES，不依赖原版 quad 排序。 */
    void sortBackToFront() {
        for (int i = 0; i < faces; i++) order[i] = i;
        IntArrays.quickSort(order, 0, faces, (left, right) -> {
            int distance = Float.compare(distances[right], distances[left]);
            return distance != 0 ? distance : Integer.compare(left, right);
        });
    }

    @SuppressWarnings("unchecked")
    M materialAtSorted(int index) { return (M) materials[order[index]]; }

    /** 按排好的次序输出原始顶点，完整保留贴图、光照、overlay 与法线。 */
    void emitSorted(int index, VertexConsumer output) {
        int face = order[index];
        int start = face * FACE_STRIDE;
        for (int vertex = 0; vertex < sizes[face]; vertex++) {
            int at = start + vertex * STRIDE;
            output.vertex(data[at], data[at + 1], data[at + 2])
                    .color((int) data[at + 3], (int) data[at + 4], (int) data[at + 5], (int) data[at + 6])
                    .uv(data[at + 7], data[at + 8])
                    .overlayCoords((int) data[at + 9], (int) data[at + 10])
                    .uv2((int) data[at + 11], (int) data[at + 12])
                    .normal(data[at + 13], data[at + 14], data[at + 15]).endVertex();
        }
    }

    /** 仅在同屏透明面首次超过容量时扩容，不按帧分配面对象。 */
    private void ensureCapacity() {
        if (faces < materials.length) return;
        int capacity = materials.length * 2;
        data = Arrays.copyOf(data, capacity * FACE_STRIDE);
        materials = Arrays.copyOf(materials, capacity);
        sizes = Arrays.copyOf(sizes, capacity);
        distances = Arrays.copyOf(distances, capacity);
        order = Arrays.copyOf(order, capacity);
    }

    /** 同一材质的顶点先攒成完整面，防止不同材质的提交交错破坏图元边界。 */
    private final class Capture implements VertexConsumer {
        /** 本接收器对应的材质。 */
        private final M material;
        /** 材质约定的图元顶点数。 */
        private final int verticesPerFace;
        /** 当前顶点属性，默认白色不透明。 */
        private final float[] current = {0, 0, 0, 255, 255, 255, 255, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        /** 当前完整面之前的临时顶点，复用数组。 */
        private final float[] pendingData = new float[FACE_STRIDE];
        /** 当前面已收到的顶点数。 */
        private int pending;

        private Capture(M material, int verticesPerFace) {
            this.material = material;
            this.verticesPerFace = verticesPerFace;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            current[0] = (float) x; current[1] = (float) y; current[2] = (float) z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            current[3] = r; current[4] = g; current[5] = b; current[6] = a; return this;
        }
        @Override public VertexConsumer uv(float u, float v) { current[7] = u; current[8] = v; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { current[9] = u; current[10] = v; return this; }
        @Override public VertexConsumer uv2(int u, int v) { current[11] = u; current[12] = v; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            current[13] = x; current[14] = y; current[15] = z; return this;
        }
        @Override public void defaultColor(int r, int g, int b, int a) { color(r, g, b, a); }
        @Override public void unsetDefaultColor() { /* 后续 color 调用会提供每顶点颜色。 */ }
        @Override public void endVertex() {
            System.arraycopy(current, 0, pendingData, pending * STRIDE, STRIDE);
            if (++pending != verticesPerFace) return;
            pending = 0;
            float x = 0, y = 0, z = 0;
            for (int vertex = 0; vertex < verticesPerFace; vertex++) {
                int at = vertex * STRIDE;
                if (!Float.isFinite(pendingData[at]) || !Float.isFinite(pendingData[at + 1])
                        || !Float.isFinite(pendingData[at + 2])) return;
                x += pendingData[at]; y += pendingData[at + 1]; z += pendingData[at + 2];
            }
            ensureCapacity();
            System.arraycopy(pendingData, 0, data, faces * FACE_STRIDE, verticesPerFace * STRIDE);
            materials[faces] = material;
            sizes[faces] = (byte) verticesPerFace;
            distances[faces] = (x * x + y * y + z * z) / (verticesPerFace * verticesPerFace);
            faces++;
        }
    }
}
