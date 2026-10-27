package org.ywzj.rvp.weapon.visual;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证屏幕裁剪不会漏掉透视球面，尤其覆盖近处、偏轴和超宽视角。 */
class RVP_PressureWaveGeometryTest {
    @Test
    void boundsContainTheWholeProjectedSphereAcrossAspectRatiosAndOffAxisViews() {
        for (float aspect : new float[] {0.7F, 1, 16F / 9, 32F / 9}) {
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(100), aspect, 0.05F, 4096);
            for (float centerX : new float[] {-3, 0, 3}) {
                // 调用正式包围框算法，以密集球面点验证保守性，而不是重复八角点实现。
                var bounds = RVP_PressureWaveGeometry.screenBounds(projection, centerX, 1, -7, 3);
                assertNotNull(bounds);
                for (int latitude = 0; latitude <= 24; latitude++) {
                    double phi = Math.PI * latitude / 24;
                    for (int longitude = 0; longitude < 48; longitude++) {
                        double theta = 2 * Math.PI * longitude / 48;
                        Vector4f point = new Vector4f(centerX + (float) (3 * Math.sin(phi) * Math.cos(theta)),
                                1 + (float) (3 * Math.cos(phi)), -7 + (float) (3 * Math.sin(phi) * Math.sin(theta)), 1)
                                .mul(projection);
                        float x = point.x / point.w;
                        float y = point.y / point.w;
                        if (x < -1 || x > 1 || y < -1 || y > 1) { continue; }
                        assertTrue(x >= bounds.left() - 0.000001F && x <= bounds.right() + 0.000001F);
                        assertTrue(y >= bounds.bottom() - 0.000001F && y <= bounds.top() + 0.000001F);
                    }
                }
            }
        }
    }

    @Test
    void cameraInsideOrNearPlaneIntersectionUsesFullScreenWithoutInfinity() {
        Matrix4f projection = new Matrix4f().perspective(1, 1.7F, 0.05F, 2048);
        assertEquals(new RVP_PressureWaveGeometry.Bounds(-1, -1, 1, 1),
                RVP_PressureWaveGeometry.screenBounds(projection, 0, 0, 0, 10));
        assertEquals(new RVP_PressureWaveGeometry.Bounds(-1, -1, 1, 1),
                RVP_PressureWaveGeometry.screenBounds(projection, 0, 0, -10.01F, 10));
    }

    @Test
    void offscreenBehindAndInvalidWavesDoNotAllocateSceneCopies() {
        Matrix4f projection = new Matrix4f().perspective(1, 1, 0.05F, 2048);
        assertNull(RVP_PressureWaveGeometry.screenBounds(projection, 100, 0, -10, 1));
        assertNull(RVP_PressureWaveGeometry.screenBounds(projection, 0, 100, -10, 1));
        assertNull(RVP_PressureWaveGeometry.screenBounds(projection, 0, 0, 10, 1));
        assertNull(RVP_PressureWaveGeometry.screenBounds(projection, 0, 0, -10, 0));
        assertNull(RVP_PressureWaveGeometry.screenBounds(projection, Float.NaN, 0, -10, 1));
    }
}
