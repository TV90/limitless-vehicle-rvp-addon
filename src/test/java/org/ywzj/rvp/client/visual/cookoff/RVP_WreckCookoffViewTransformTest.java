package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 回归：天气阶段已有视图矩阵，CPU 再乘相机矩阵会使固定喷口随转头漂移。 */
class RVP_WreckCookoffViewTransformTest {
    @Test
    void changingYawPitchAndRollStillReconstructsTheSameWorldAnchor() {
        Vec3 world = new Vec3(125, 72, -340);
        Vec3 camera = new Vec3(110, 70, -355);
        for (float yaw : new float[]{0, 0.6f, 2.1f}) {
            for (float pitch : new float[]{-0.8f, 0, 0.7f}) {
                Matrix4f view = new Matrix4f().rotateZ(0.12f).rotateX(pitch).rotateY(yaw);
                // 调用生产坐标入口，模拟天气阶段着色器对原始顶点只执行一次视图变换。
                Vec3 relative = RVP_WreckCookoffGeometry.cameraRelative(world, camera);
                Vector3f eye = view.transformPosition(relative.toVector3f(), new Vector3f());
                Vector3f restored = new Matrix4f(view).invert().transformPosition(eye, new Vector3f());
                assertEquals(world.x, restored.x + camera.x, 1e-4);
                assertEquals(world.y, restored.y + camera.y, 1e-4);
                assertEquals(world.z, restored.z + camera.z, 1e-4);
                // 旧路径同时在 CPU/着色器乘 view；反投影会还原到另一个世界位置。
                Vector3f doubledEye = view.transformPosition(eye, new Vector3f());
                Vector3f oldRestored = new Matrix4f(view).invert().transformPosition(doubledEye, new Vector3f());
                assertTrue(oldRestored.distance(relative.toVector3f()) > 0.1,
                        "此用例必须能识别旧版双重视图变换，避免恒等视角掩盖漂移");
            }
        }
    }

    @Test
    void movingCameraChangesOnlyRelativePositionNotWorldAnchor() {
        Vec3 world = new Vec3(12.5, 68, -4.25);
        for (Vec3 camera : new Vec3[]{new Vec3(0, 64, 0), new Vec3(18, 75, 8), new Vec3(-8, 69, -16)}) {
            // 调用生产入口，绕车移动时反加相机位置必须精确回到同一个喷口。
            assertEquals(world, RVP_WreckCookoffGeometry.cameraRelative(world, camera).add(camera));
        }
    }

    @Test
    void largeWorldCoordinatesKeepSmallLocalOffsets() {
        Vec3 camera = new Vec3(25000000, 80, -25000000);
        Vec3 anchor = camera.add(0.125, 0.25, -0.375);
        // 调用生产入口，大坐标相减先于 float 顶点量化，保留亚格级局部位移。
        Vec3 relative = RVP_WreckCookoffGeometry.cameraRelative(anchor, camera);
        assertEquals(new Vec3(0.125, 0.25, -0.375), relative);
    }
}
