package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 烟雾实际几何、原始透明度及帧隔离验证，不启动游戏或显卡。 */
class RVP_SmokeLightGeometryTest {
    @Test
    void capturedQuadRetainsCoordinatesAtlasUVAndAlpha() {
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        VertexConsumer capture = geometry.capture(null, 7);
        quad(capture, 0, -3, 64);
        var batch = geometry.batches().iterator().next();
        assertEquals(4, geometry.vertexCount());
        assertEquals(7, batch.texture);
        assertEquals(-1, batch.data[0]);
        assertEquals(-3, batch.data[2]);
        assertEquals(0.25F, batch.data[3]);
        assertEquals(0.75F, batch.data[4]);
        assertEquals(64F / 255F, batch.data[8]);
    }

    @Test
    void wrappingPreservesTheOriginalDrawAndOnlyCopiesItsVertices() {
        RVP_SmokeLightGeometry first = new RVP_SmokeLightGeometry();
        RVP_SmokeLightGeometry second = new RVP_SmokeLightGeometry();
        VertexConsumer original = first.capture(null, 7);
        VertexConsumer wrapped = second.capture(original, 8);
        quad(wrapped, 3, -5, 128);
        assertArrayEquals(first.batches().iterator().next().data, second.batches().iterator().next().data);
        assertEquals(4, first.vertexCount());
        assertEquals(4, second.vertexCount());
    }

    @Test
    void frameAndResourceResetsCannotRelightOldSmoke() {
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        quad(geometry.capture(null, 7), 0, -3, 128);
        geometry.beginFrame();
        assertEquals(0, geometry.vertexCount());
        assertEquals(0, geometry.batches().iterator().next().size);
        quad(geometry.capture(null, 7), 0, -3, 128);
        geometry.clear();
        assertTrue(geometry.batches().isEmpty());
    }

    @Test
    void onlyQuadsIntersectingARealLightAreSubmitted() {
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        quad(geometry.capture(null, 7), 0, -3, 128);
        var batch = geometry.batches().iterator().next();
        RVP_ScreenLight near = light(new Vec3(0, 0, -3));
        RVP_ScreenLight far = light(new Vec3(100, 0, -3));
        assertTrue(batch.intersects(0, List.of(near), Vec3.ZERO));
        assertFalse(batch.intersects(0, List.of(far), Vec3.ZERO));
        assertFalse(batch.intersects(0, List.of(), Vec3.ZERO));
    }

    @Test
    void invalidVerticesAndExcessiveGeometryRemainFiniteAndBounded() {
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        VertexConsumer capture = geometry.capture(null, 7);
        capture.vertex(Double.NaN, 0, 0).uv(0, 0).color(255, 255, 255, 255).endVertex();
        var batch = geometry.batches().iterator().next();
        assertEquals(0, batch.data[8]);
        for (int index = 1; index < RVP_SmokeLightGeometry.MAX_VERTICES + 16; index++) {
            capture.vertex(0, 0, -3).endVertex();
        }
        assertEquals(RVP_SmokeLightGeometry.MAX_VERTICES, geometry.vertexCount());
        assertEquals(0, geometry.vertexCount() % 4);
        for (int index = 0; index < batch.size; index++) { assertTrue(Float.isFinite(batch.data[index])); }
    }

    @Test
    void sameTextureInParticleAndMuzzleSpacesMustStayInSeparateBatches() {
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        quad(geometry.capture(null, 7), 0, -3, 64);
        quad(geometry.capture(null, 7, true), 4, -5, 128);
        var batches = geometry.batches().stream().toList();
        assertEquals(2, batches.size());
        assertFalse(batches.get(0).viewSpace);
        assertTrue(batches.get(1).viewSpace);
        assertEquals(batches.get(0).texture, batches.get(1).texture);
        assertEquals(4 * RVP_SmokeLightGeometry.STRIDE, batches.get(0).size);
        assertEquals(4 * RVP_SmokeLightGeometry.STRIDE, batches.get(1).size);
        assertEquals(8, geometry.vertexCount());
        geometry.beginFrame();
        assertTrue(geometry.batches().stream().allMatch(batch -> batch.size == 0));
    }

    @Test
    void rotatedCameraCullsMuzzleAgainstTheUploadedViewSpaceLights() {
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        Vec3 camera = new Vec3(1_000_000, 64, -1_000_000);
        Vec3 position = camera.add(10, 0, 0);
        Matrix4f view = new Matrix4f().rotateY((float) Math.PI / 2);
        Vector3f center = view.transformPosition(new Vector3f(10, 0, 0));
        quad(geometry.capture(null, 7, true), center.x, center.z, 128);
        var batch = geometry.batches().iterator().next();
        List<RVP_ScreenLight> lights = List.of(light(position));
        // 旧式世界坐标剔除会把此炮口误判为远离光源；正式上传后的观察空间应保留它。
        assertFalse(batch.intersects(0, lights, camera));
        assertTrue(batch.intersects(0, lights, camera, new float[] {center.x, center.y, center.z, 7}));
        assertFalse(batch.intersects(0, lights, camera, new float[] {100, 0, -10, 7}));
    }

    @Test
    void modelPackedVertexRetainsItsFinalTransformTextureAndFade() {
        RVP_SmokeLightGeometry original = new RVP_SmokeLightGeometry();
        RVP_SmokeLightGeometry geometry = new RVP_SmokeLightGeometry();
        VertexConsumer capture = geometry.capture(original.capture(null, 8), 7, true);
        Matrix4f boneAndView = new Matrix4f().translate(2, 3, -5).rotateY((float) Math.PI / 2);
        Vector3f vertex = boneAndView.transformPosition(new Vector3f(1, 0, 0));
        // 使用 SBM 实际采用的整顶点入口，验证它不会绕过记录或给原绘制丢失属性。
        capture.vertex(vertex.x, vertex.y, vertex.z, 1, 0.5F, 0.25F, 0.4F,
                0.125F, 0.875F, 0, 15728880, 0, 1, 0);
        var batch = geometry.batches().iterator().next();
        assertEquals(1, geometry.vertexCount());
        assertEquals(2, batch.data[0], 0.00001F);
        assertEquals(3, batch.data[1], 0.00001F);
        assertEquals(-6, batch.data[2], 0.00001F);
        assertEquals(0.125F, batch.data[3]);
        assertEquals(0.875F, batch.data[4]);
        assertEquals(102F / 255F, batch.data[8]);
        assertArrayEquals(original.batches().iterator().next().data, batch.data);
    }

    /** 使用图集部分 UV 与非满 Alpha 构造真实四边形输入。 */
    private static void quad(VertexConsumer capture, float x, float z, int alpha) {
        for (int index = 0; index < 4; index++) {
            capture.vertex(x + (index < 2 ? -1 : 1), index % 2 == 0 ? -1 : 1, z)
                    .uv(0.25F, 0.75F).color(64, 96, 128, alpha).uv2(0, 240).endVertex();
        }
    }

    /** 普通表面光快照，烟雾不能因光源类型不同而失去受光。 */
    private static RVP_ScreenLight light(Vec3 position) {
        return new RVP_ScreenLight(position, 1, 0.5F, 0.1F, 7, 7, 4, 1,
                RVP_LightSourceType.ROCKET_ENGINE, null, 0.8F, true, 0);
    }
}
