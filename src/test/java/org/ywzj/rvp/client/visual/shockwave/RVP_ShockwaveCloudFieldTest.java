package org.ywzj.rvp.client.visual.shockwave;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveCloudField.*;

class RVP_ShockwaveCloudFieldTest {
    /** 固定外观参数，排除游戏世界和显卡依赖。 */
    private static final Appearance APPEARANCE = new Appearance() {
        public Vec3 center() { return new Vec3(100000, 64, -100000); }
        public float cloudScale() { return 2; }
        public int age() { return 80; }
        public int maxAge() { return 220; }
        public double greying() { return 1; }
    };

    @Test
    void everyCloudTypeIsDeterministicFiniteAndExpires() {
        for (CloudType type : CloudType.values()) {
            Cloud a = new Cloud(100000, 64, -100000, 0, 220, type, 1, 4, new Random(123));
            Cloud b = new Cloud(100000, 64, -100000, 0, 220, type, 1, 4, new Random(123));
            for (int age = 1; age <= 221; age++) {
                var context = new UpdateContext(100000, 64, -100000, 8, 5, 2, 10, 0.5, 10, age);
                // 两份同种子状态逐 tick 推进，验证共享算法与渲染次数无关。
                a.update(context);
                b.update(context);
                assertEquals(a.renderX(APPEARANCE, 0.5F), b.renderX(APPEARANCE, 0.5F));
                assertTrue(Double.isFinite(a.renderY(APPEARANCE, 0.5F)));
                assertTrue(Double.isFinite(a.renderZ(APPEARANCE, 0.5F)));
                assertTrue(a.alpha(APPEARANCE) >= 0 && a.alpha(APPEARANCE) <= 1);
            }
            assertTrue(a.dead);
            assertEquals(0, a.alpha(APPEARANCE));
        }
    }

    @Test
    void meshIsFiniteAndUsesUnitSpherePositions() {
        float[] vertices = RVP_ShockwaveMesh.buildSphereMesh();
        assertEquals(16 * 48 * 4 * 5, vertices.length);
        for (int index = 0; index < vertices.length; index += 5) {
            double radius = Math.sqrt(vertices[index] * vertices[index] + vertices[index + 1] * vertices[index + 1]
                    + vertices[index + 2] * vertices[index + 2]);
            assertEquals(1, radius, 0.001);
            assertTrue(vertices[index + 3] >= 0 && vertices[index + 3] <= 1);
            assertTrue(vertices[index + 4] >= 0 && vertices[index + 4] <= 1);
        }
    }
}
