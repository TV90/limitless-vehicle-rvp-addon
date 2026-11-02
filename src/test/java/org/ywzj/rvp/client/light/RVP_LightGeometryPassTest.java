package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 贴车爆炸的接触判定：静止姿态、盒面容差、旋转与远坐标均不能让光源豁免逐帧反转。 */
class RVP_LightGeometryPassTest {
    @Test
    void stationaryRotationIsBitIdenticalAcrossPartialTicksAndDoesNotMutateVehicleState() {
        Quaternionf current = new Quaternionf().rotationXYZ(.13F, .61F, .07F);
        Quaternionf previous = new Quaternionf(current);
        Quaternionf original = new Quaternionf(current);
        // 调用正式插值；相同姿态的各显示帧必须逐位一致，不能只按视觉角度近似相等。
        Quaternionf expected = RVP_LightGeometryPass.interpolateBodyRotation(previous, current, 0);
        for (int frame = 0; frame <= 120; frame++) {
            assertEquals(expected, RVP_LightGeometryPass.interpolateBodyRotation(previous, current, frame / 120F));
        }
        assertEquals(original, current);
        assertEquals(original, previous);
        assertNotSame(current, expected);
        assertEquals(1F, expected.lengthSquared(), 0.000001F);
        assertEquals(expected, RVP_LightGeometryPass.interpolateBodyRotation(null, current, .5F));
    }

    @Test
    void movingRotationStillInterpolatesBetweenTheActualPoses() {
        Quaternionf previous = new Quaternionf(), current = new Quaternionf().rotationY((float) Math.PI / 2);
        // 调用正式插值，运动中的车体仍应按中间姿态挡光，不冻结旧盒或直接跳到新盒。
        Quaternionf middle = RVP_LightGeometryPass.interpolateBodyRotation(previous, current, .5F);
        Vector3f direction = middle.transform(new Vector3f(0, 0, 1));
        assertEquals(Math.sqrt(.5), direction.x, 0.000001);
        assertEquals(Math.sqrt(.5), direction.z, 0.000001);
        assertEquals(1F, Math.abs(previous.dot(RVP_LightGeometryPass.interpolateBodyRotation(previous, current, 0))), 0.000001F);
        assertEquals(1F, Math.abs(current.dot(RVP_LightGeometryPass.interpolateBodyRotation(previous, current, 1))), 0.000001F);
    }

    @Test
    void allRotatedFacesStayInContactAtNearAndLargeWorldCoordinates() {
        Vector3f extents = new Vector3f(1.25F, .75F, 1F);
        for (Vec3 center : new Vec3[] {new Vec3(.174, -.125, -4.365), new Vec3(25_000_000.174, 150, -25_000_004.365)}) {
            for (int angle = 1; angle <= 80; angle++) {
                Quaternionf current = new Quaternionf().rotationXYZ(.13F, (float) Math.toRadians(angle), .07F);
                for (int axis = 0; axis < 3; axis++) {
                    for (int sign : new int[] {-1, 1}) {
                        Vector3d point = new Vector3d();
                        point.setComponent(axis, sign * extents.get(axis));
                        // 用固定世界空间盒面构造爆心；推进的只有显示帧部分刻。
                        Vector3d worldPoint = new Quaterniond(current).normalize().transform(point);
                        Vec3 source = center.add(worldPoint.x, worldPoint.y, worldPoint.z);
                        for (int frame = 0; frame <= 20; frame++) {
                            // 调用实际姿态及接触入口，确认各朝向、正反盒面、远坐标均保持同一判定。
                            Quaternionf rotation = RVP_LightGeometryPass.interpolateBodyRotation(current, current, frame / 20F);
                            assertTrue(RVP_LightGeometryPass.sourceTouchesBody(source, center, extents, rotation));
                        }
                    }
                }
            }
        }
    }

    @Test
    void contactMarginDoesNotExemptSeparateWallsOrOtherVehicles() {
        Vec3 center = new Vec3(10, 20, 30);
        Vector3f extents = new Vector3f(1, 1, 1);
        Quaternionf rotation = new Quaternionf();
        // 调用正式边界：仅盒内和2毫米接触层豁免，明确隔开的遮挡体仍需阻断光路。
        assertTrue(RVP_LightGeometryPass.sourceTouchesBody(center, center, extents, rotation));
        assertTrue(RVP_LightGeometryPass.sourceTouchesBody(center.add(1.001, 0, 0), center, extents, rotation));
        assertFalse(RVP_LightGeometryPass.sourceTouchesBody(center.add(1.01, 0, 0), center, extents, rotation));
        assertFalse(RVP_LightGeometryPass.sourceTouchesBody(center.add(0, -1.01, 0), center, extents, rotation));
        assertFalse(RVP_LightGeometryPass.sourceTouchesBody(center.add(0, 0, 1.01), center, extents, rotation));
    }
}
