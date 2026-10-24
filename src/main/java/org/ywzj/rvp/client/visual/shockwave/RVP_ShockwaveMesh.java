package org.ywzj.rvp.client.visual.shockwave;

import net.minecraft.util.Mth;

/** 共享球壳网格；每个顶点保存位置与纹理坐标。 */
public final class RVP_ShockwaveMesh {
    /** 纬向分段数。 */
    private static final int RINGS = 16;
    /** 经向分段数。 */
    private static final int SEGMENTS = 48;
    private RVP_ShockwaveMesh() { }

    public static float[] buildSphereMesh() {
        float[] vertices = new float[RINGS * SEGMENTS * 4 * 5];
        int offset = 0;
        for (int ring = 0; ring < RINGS; ring++) {
            float v0 = ring / (float) RINGS;
            float v1 = (ring + 1) / (float) RINGS;
            float theta0 = (float) (Math.PI * v0);
            float theta1 = (float) (Math.PI * v1);
            for (int segment = 0; segment < SEGMENTS; segment++) {
                float u0 = segment / (float) SEGMENTS;
                float u1 = (segment + 1) / (float) SEGMENTS;
                float phi0 = (float) (Math.PI * 2.0D * u0);
                float phi1 = (float) (Math.PI * 2.0D * u1);
                offset = putVertex(vertices, offset, theta0, phi0, u0, v0);
                offset = putVertex(vertices, offset, theta1, phi0, u0, v1);
                offset = putVertex(vertices, offset, theta1, phi1, u1, v1);
                offset = putVertex(vertices, offset, theta0, phi1, u1, v0);
            }
        }
        return vertices;
    }

    private static int putVertex(float[] vertices, int offset,
            float theta, float phi, float u, float v) {
        float sinTheta = Mth.sin(theta);
        vertices[offset++] = sinTheta * Mth.cos(phi);
        vertices[offset++] = Mth.cos(theta);
        vertices[offset++] = sinTheta * Mth.sin(phi);
        vertices[offset++] = u;
        vertices[offset++] = v;
        return offset;
    }

}
