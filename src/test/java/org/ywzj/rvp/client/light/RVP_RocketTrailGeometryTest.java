package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry.Bounds;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** 验证跨屏长烟、细烟矩形和近景密烟的实际几何/面积预算。 */
class RVP_RocketTrailGeometryTest {
    /** 测试观察矩阵，相机沿负 Z 轴看。 */
    private final Matrix4f view = new Matrix4f();
    /** 测试九十度视野，最远一万格，覆盖远距尾烟。 */
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, 0.1F, 10000);

    /** 调用生产投影，两个端点都在屏幕外的烟仍应横穿整个画面。 */
    @Test
    void bothEndpointsOffscreenStillKeepCrossingTrail() {
        var bounds = RVP_RocketTrailGeometry.bounds(segment(-40, 40, -10), view, projection, Vec3.ZERO);
        assertNotNull(bounds);
        assertEquals(-1, bounds.left()); assertEquals(1, bounds.right());
        assertTrue(bounds.top() - bounds.bottom() < 0.2F);
    }

    /** 调用生产投影，细长尾迹的矩形不能被错误扩成大正方形。 */
    @Test
    void longThinTrailUsesEndpointWidthsInsteadOfLengthAsHeight() {
        var bounds = RVP_RocketTrailGeometry.bounds(segment(-20, 20, -50), view, projection, Vec3.ZERO);
        assertNotNull(bounds);
        assertTrue(bounds.right() - bounds.left() > 0.7F);
        assertTrue(bounds.top() - bounds.bottom() < 0.03F);
    }

    /** 调用生产投影，身处烟内允许全屏，全部在身后则无贡献。 */
    @Test
    void cameraInsideIsFullscreenAndBehindCameraIsRejected() {
        assertEquals(new Bounds(-1, -1, 1, 1),
                RVP_RocketTrailGeometry.bounds(segment(-1, 1, -0.1F), view, projection, Vec3.ZERO));
        assertNull(RVP_RocketTrailGeometry.bounds(segment(-1, 1, 10), view, projection, Vec3.ZERO));
    }

    /** 调用生产预算，保留最近的烟并限制总面积，返回顺序仍是由远到近。 */
    @Test
    void overlappingFullscreenSmokeHasBoundedPixelWorkAndKeepsNearestLayers() {
        var input = new ArrayList<RVP_RocketTrailSegment>();
        for (int index = 0; index < 100; index++) { input.add(segment(-1, 1, -100 + index)); }
        var result = RVP_RocketTrailGeometry.budget(input, Vec3.ZERO, segment -> new Bounds(-1, -1, 1, 1));
        assertEquals((int) RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE, result.size());
        assertTrue(result.stream().mapToDouble(RVP_RocketTrailGeometry.Projected::area).sum()
                <= RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE);
        assertEquals(input.get(99), result.get(result.size() - 1).segment());
        assertTrue(result.get(0).segment().center().z < result.get(result.size() - 1).segment().center().z);
    }

    /** 调用实际面积预算，三团包住相机的整屏烟不能把远处正常尾烟完全挤掉。 */
    @Test
    void enclosingSmokeKeepsExteriorTrailVisibleWithoutIncreasingTotalAreaBudget() {
        var distant = segment(-2, 2, -20);
        var input = new ArrayList<RVP_RocketTrailSegment>();
        input.add(distant);
        for (int index = 0; index < 3; index++) {
            input.add(new RVP_RocketTrailSegment(index + 2, Vec3.ZERO, Vec3.ZERO, 3, 3,
                    0.3F, 0.3F, 20, 0.3F, 0.6F, 0.7F));
        }
        var result = RVP_RocketTrailGeometry.budget(input, Vec3.ZERO,
                sample -> RVP_RocketTrailGeometry.bounds(sample, view, projection, Vec3.ZERO));
        assertTrue(result.stream().anyMatch(value -> value.segment() == distant), "烟外尾迹不能因相机在烟内而消失");
        assertTrue(result.stream().anyMatch(value -> value.segment().contains(Vec3.ZERO)), "眼前的烟也必须继续绘制");
        assertEquals(distant, result.get(0).segment(), "分配份额后仍需保持从远到近合成");
        assertTrue(result.stream().mapToDouble(RVP_RocketTrailGeometry.Projected::area).sum()
                <= RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE + 0.000001);
    }

    /** 调用真实的变化半径烟段包含判定，排除很长但离眼睛很远的烟。 */
    @Test
    void cameraContainmentUsesActualSegmentRadiusInsteadOfItsBoundingSphere() {
        var longTrail = new RVP_RocketTrailSegment(1, new Vec3(-50, 10, 0), new Vec3(50, 10, 0),
                1, 3, 1, 1, 20, 0.3F, 0.6F, 0.7F);
        assertFalse(longTrail.contains(Vec3.ZERO));
        assertTrue(longTrail.contains(new Vec3(40, 12, 0)));
        assertFalse(longTrail.contains(new Vec3(-40, 12, 0)));
        assertTrue(longTrail.contains(new Vec3(-50.5, 10, 0)), "端部圆帽也属于烟团");
    }

    /** 调用实际预算，无烟外输入时允许近处烟用足当前扩大的整屏额度。 */
    @Test
    void noExteriorSmokeDoesNotWasteInteriorBudget() {
        var input = new ArrayList<RVP_RocketTrailSegment>();
        int limit = (int) RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE;
        for (int index = 0; index < limit + 5; index++) {
            input.add(new RVP_RocketTrailSegment(index, Vec3.ZERO, Vec3.ZERO, 3, 3,
                    0.3F, 0.3F, 20, 0.3F, 0.6F, 0.7F));
        }
        var result = RVP_RocketTrailGeometry.budget(input, Vec3.ZERO, sample -> new Bounds(-1, -1, 1, 1));
        assertEquals(limit, result.size());
    }

    /** 调用真实预算，烟外的大体积也能获得份额，而不是只允许面积很小的远处烟线。 */
    @Test
    void largeExteriorVolumesRetainReservedCoverageWhileInsideSmoke() {
        var input = new ArrayList<RVP_RocketTrailSegment>();
        int exteriorLimit = (int) RVP_RocketTrailParameters.EXTERIOR_SCREEN_RESERVE;
        int totalLimit = (int) RVP_RocketTrailParameters.MAX_SCREEN_COVERAGE;
        for (int index = 0; index < exteriorLimit + 4; index++) {
            input.add(segment(-1, 1, -100 + index));
        }
        for (int index = 0; index < totalLimit + 4; index++) {
            input.add(new RVP_RocketTrailSegment(index + 10, Vec3.ZERO, Vec3.ZERO, 3, 3,
                    0.3F, 0.3F, 20, 0.3F, 0.6F, 0.7F));
        }
        var result = RVP_RocketTrailGeometry.budget(input, Vec3.ZERO, sample -> new Bounds(-1, -1, 1, 1));
        assertEquals(totalLimit, result.size());
        assertEquals(exteriorLimit, result.stream().filter(value -> !value.segment().contains(Vec3.ZERO)).count());
        assertEquals(totalLimit - exteriorLimit, result.stream().filter(value -> value.segment().contains(Vec3.ZERO)).count());
    }

    /** 创建可供实际几何函数投影的固定世界烟段。 */
    private static RVP_RocketTrailSegment segment(float startX, float endX, float z) {
        return new RVP_RocketTrailSegment(1, new Vec3(startX, 0, z), new Vec3(endX, 0, z),
                0.5F, 0.5F, 1, 1, 20, 0.3F, 0.7F, 0.7F);
    }
}
