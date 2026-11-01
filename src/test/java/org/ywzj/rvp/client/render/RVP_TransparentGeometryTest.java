package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RVP_TransparentGeometryTest {
    @Test
    void sortsTrianglesAndQuadsTogetherAcrossMaterials() {
        var geometry = new RVP_TransparentGeometry<String>();
        face(geometry.capture("nearQuad", 4), 4, -2);
        face(geometry.capture("farTriangle", 3), 3, -20);
        face(geometry.capture("middleTriangle", 3), 3, -8);
        geometry.sortBackToFront();
        assertEquals(List.of("farTriangle", "middleTriangle", "nearQuad"),
                List.of(geometry.materialAtSorted(0), geometry.materialAtSorted(1), geometry.materialAtSorted(2)));
        Recording output = new Recording();
        for (int i = 0; i < geometry.faceCount(); i++) geometry.emitSorted(i, output);
        assertEquals(10, output.vertices.size());
        assertEquals(-20, output.vertices.get(0)[2]);
        assertEquals(-2, output.vertices.get(9)[2]);
    }

    @Test
    void preservesVertexAttributesWindingAndSameDistanceOrder() {
        var geometry = new RVP_TransparentGeometry<String>();
        var input = geometry.capture("glass", 3);
        for (int vertex = 0; vertex < 3; vertex++) {
            input.vertex(vertex, vertex + 1, -5).color(13, 127, 254, 89)
                    .uv(.125f, .875f).overlayCoords(3, 10).uv2(160, 240).normal(0, 1, 0).endVertex();
        }
        var second = geometry.capture("sameDepth", 3);
        for (int vertex = 0; vertex < 3; vertex++) second.vertex(vertex, vertex + 1, -5).endVertex();
        geometry.sortBackToFront();
        assertEquals("glass", geometry.materialAtSorted(0));
        Recording output = new Recording();
        geometry.emitSorted(0, output);
        for (int vertex = 0; vertex < 3; vertex++) {
            assertArrayEquals(new float[]{vertex, vertex + 1, -5, 13, 127, 254, 89, .125f, .875f,
                    3, 10, 160, 240, 0, 1, 0}, output.vertices.get(vertex));
        }
    }

    @Test
    void newFrameDropsOldAndIncompleteFacesWithoutMixingMaterials() {
        var geometry = new RVP_TransparentGeometry<String>();
        var triangle = geometry.capture("triangle", 3);
        var quad = geometry.capture("quad", 4);
        triangle.vertex(0, 0, -100).endVertex();
        face(quad, 4, -10);
        assertEquals(1, geometry.faceCount());
        geometry.beginFrame();
        assertEquals(0, geometry.faceCount());
        face(triangle, 3, -1);
        assertEquals(1, geometry.faceCount());
        geometry.sortBackToFront();
        Recording output = new Recording();
        geometry.emitSorted(0, output);
        assertEquals(3, output.vertices.size());
        assertTrue(output.vertices.stream().allMatch(v -> v[2] == -1));
    }

    @Test
    void expandingReusableStorageDoesNotDropFaces() {
        var geometry = new RVP_TransparentGeometry<String>();
        var input = geometry.capture("glass", 3);
        for (int i = 1; i <= 1500; i++) face(input, 3, -i);
        geometry.sortBackToFront();
        assertEquals(1500, geometry.faceCount());
        Recording output = new Recording();
        geometry.emitSorted(0, output);
        geometry.emitSorted(1499, output);
        assertEquals(-1500, output.vertices.get(0)[2]);
        assertEquals(-1, output.vertices.get(3)[2]);
    }

    private static void face(VertexConsumer output, int size, float z) {
        for (int i = 0; i < size; i++) output.vertex(i % 2, i / 2, z).endVertex();
    }

    private static final class Recording implements VertexConsumer {
        private final List<float[]> vertices = new ArrayList<>();
        private final float[] current = new float[16];
        @Override public VertexConsumer vertex(double x, double y, double z) { current[0] = (float) x; current[1] = (float) y; current[2] = (float) z; return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { current[3] = r; current[4] = g; current[5] = b; current[6] = a; return this; }
        @Override public VertexConsumer uv(float u, float v) { current[7] = u; current[8] = v; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { current[9] = u; current[10] = v; return this; }
        @Override public VertexConsumer uv2(int u, int v) { current[11] = u; current[12] = v; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { current[13] = x; current[14] = y; current[15] = z; return this; }
        @Override public void endVertex() { vertices.add(current.clone()); }
        @Override public void defaultColor(int r, int g, int b, int a) { color(r, g, b, a); }
        @Override public void unsetDefaultColor() {}
    }
}
