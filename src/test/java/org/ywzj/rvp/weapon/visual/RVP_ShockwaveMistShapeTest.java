package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.Test;
import java.util.List;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveMistShape.Vertex;
import static org.junit.jupiter.api.Assertions.*;

/** 冲击云分布和地形裁切回归，重点防止地下云片与固定圆壳。 */
class RVP_ShockwaveMistShapeTest {
    @Test
    void groundBurstHasOnlyUpperCloudsAndAirBurstRetainsBothHemispheres() {
        // 调用正式分布生成入口；不把下半球折叠成贴地圆圈。
        var ground = RVP_ShockwaveMistShape.create(42, 256, true);
        var air = RVP_ShockwaveMistShape.create(42, 256, false);
        assertEquals(256, ground.size());
        assertTrue(ground.stream().allMatch(p -> p.y() >= 0));
        assertTrue(air.stream().anyMatch(p -> p.y() < -0.5));
        assertTrue(air.stream().anyMatch(p -> p.y() > 0.5));
        assertTrue(ground.stream().allMatch(p -> Math.abs(p.x()*p.x()+p.y()*p.y()+p.z()*p.z()-1) < 1e-12));
    }

    @Test
    void cloudLayerHasRealRadialSpreadAndStableSeed() {
        // 调用径向分布，云团既在厚层内又稳定不闪烁，而不是全部落在同一个球面。
        var puffs = RVP_ShockwaveMistShape.create(7, 256, true);
        assertEquals(puffs, RVP_ShockwaveMistShape.create(7, 256, true));
        assertNotEquals(puffs, RVP_ShockwaveMistShape.create(8, 256, true));
        double min = puffs.stream().mapToDouble(p -> p.radialFactor(0.5F)).min().orElseThrow();
        double max = puffs.stream().mapToDouble(p -> p.radialFactor(0.5F)).max().orElseThrow();
        assertTrue(min >= 0.5 && min < 0.55);
        assertTrue(max > 0.9 && max <= 0.925001);
        assertEquals(512, RVP_ShockwaveMistShape.create(0, 100000, false).size());
    }

    @Test
    void crossingCloudIsClippedAtGroundWithInterpolatedTextureCoordinates() {
        // 调用正式多边形裁切；底部在地下时必须切掉，不能抬高整团或拉伸原UV。
        var clipped = RVP_ShockwaveMistShape.clipAbove(quad(), 0);
        assertEquals(4, clipped.size());
        assertTrue(clipped.stream().allMatch(v -> v.y() >= 0));
        var boundary = clipped.stream().filter(v -> v.y() == 0).toList();
        assertEquals(2, boundary.size());
        assertTrue(boundary.stream().allMatch(v -> v.v() == 0.5F));
        assertEquals(2, clipped.stream().filter(v -> v.y() == 1).count());
    }

    @Test
    void terrainAboveCloudAndUnknownTerrainDrawNothing() {
        // 调用不同地形高度，验证高坡整团遮挡、远处未知区块不画和无遮挡保留。
        assertTrue(RVP_ShockwaveMistShape.clipAbove(quad(), 2).isEmpty());
        assertTrue(RVP_ShockwaveMistShape.clipAbove(quad(), Double.POSITIVE_INFINITY).isEmpty());
        assertTrue(RVP_ShockwaveMistShape.clipAbove(quad(), Double.NaN).isEmpty());
        assertEquals(quad(), RVP_ShockwaveMistShape.clipAbove(quad(), -2));
    }

    @Test
    void slantedCloudClippingCanProduceTriangleWithoutBelowGroundVertices() {
        var diamond = List.of(new Vertex(0,-2,0,0.5F,1),new Vertex(2,0,0,1,0.5F),
                new Vertex(0,2,0,0.5F,0),new Vertex(-2,0,0,0,0.5F));
        // 调用斜云片裁切，验证三角化前的顶点合法且新边界UV连续。
        var clipped = RVP_ShockwaveMistShape.clipAbove(diamond, 1);
        assertEquals(3, clipped.size());
        assertTrue(clipped.stream().allMatch(v -> v.y() >= 1 && v.u() >= 0 && v.u() <= 1 && v.v() >= 0 && v.v() <= 1));
    }

    private static List<Vertex> quad() {
        return List.of(new Vertex(-1,-1,0,0,1),new Vertex(1,-1,0,1,1),
                new Vertex(1,1,0,1,0),new Vertex(-1,1,0,0,0));
    }
}
