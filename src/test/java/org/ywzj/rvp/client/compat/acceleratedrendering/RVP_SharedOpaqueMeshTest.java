package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedVertexData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RVP_SharedOpaqueMeshTest {
    @Test
    void rigidAnimationAndUniformScaleCanShareUnchangedGeometry() {
        for (float scale : new float[]{1, 0.1F, 2, -1}) {
            Matrix4f pose = new Matrix4f().translate(100, -20, 30).rotateXYZ(0.2F, 1.5F, -0.8F).scale(scale);
            assertTrue(RVP_SharedOpaqueMesh.supportsTransform(pose, pose.normal(new Matrix3f())));
        }
    }

    @Test
    void scaledOrInvalidPosesMustKeepTheOriginalNormalPipeline() {
        Matrix4f nonuniform = new Matrix4f().rotateY(0.8F).scale(1, 2, 1);
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(nonuniform, nonuniform.normal(new Matrix3f())));
        Matrix4f shear = new Matrix4f().m10(0.2F);
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(shear, shear.normal(new Matrix3f())));
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(new Matrix4f().scale(0), new Matrix3f()));
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(new Matrix4f().m00(Float.NaN), new Matrix3f()));
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(new Matrix4f(), new Matrix3f().m11(Float.POSITIVE_INFINITY)));
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(new Matrix4f().m03(0.5F), new Matrix3f()));
    }

    @Test
    void customNormalOrientationIsNotSilentlyReplaced() {
        Matrix4f pose = new Matrix4f().rotateY(0.8F);
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(pose, new Matrix3f()));
        assertFalse(RVP_SharedOpaqueMesh.supportsTransform(pose, pose.normal(new Matrix3f()).scale(-1)));
    }

    @Test
    void panelMirrorPreservesBothProjectedPositionAndOriginalLighting() {
        for (float yaw : new float[]{-2.8F, 0, 1.1F}) {
            Matrix3f normal = new Matrix3f().rotateXYZ(3.05F, yaw, 0.15F);
            Matrix4f pose = new Matrix4f().translation(510, 95, 100).rotateXYZ(3.05F, yaw, 0.15F)
                    .scale(37, 37, -37).translate(-1, -0.5F, 2);
            var split = RVP_SharedOpaqueMesh.panelTransform(pose, normal);
            assertNotNull(split);
            Matrix4f combined = new Matrix4f(split.viewSuffix()).mul(split.meshPose());
            Vector3f point = new Vector3f(2, -3, 4);
            assertTrue(combined.transformPosition(new Vector3f(point))
                    .equals(pose.transformPosition(new Vector3f(point)), 0.001F));
            Vector3f light = new Vector3f(0.2F, 1, -0.7F).normalize();
            Vector3f vertexNormal = new Vector3f(0.3F, 0.8F, -0.2F).normalize();
            float original = light.dot(normal.transform(new Vector3f(vertexNormal)).normalize());
            Vector3f localLight = new Matrix3f(split.meshPose()).invert().transform(new Vector3f(light)).normalize();
            assertEquals(original, localLight.dot(vertexNormal), 0.00001F);
            assertTrue(RVP_SharedOpaqueMesh.supportsTransform(split.meshPose(), normal));
        }
    }

    @Test
    void panelRejectsNonuniformOrDegenerateNormalsInsteadOfChangingLighting() {
        assertNull(RVP_SharedOpaqueMesh.panelTransform(new Matrix4f().scale(1, 2, -1), new Matrix3f()));
        assertNull(RVP_SharedOpaqueMesh.panelTransform(new Matrix4f(), new Matrix3f().scale(1, 0.5F, 1)));
        assertNull(RVP_SharedOpaqueMesh.panelTransform(new Matrix4f(), new Matrix3f().zero()));
    }

    @Test
    void capturePreservesTriangleOrderNormalsUvsAndUsesInstanceLighting() {
        float[] positions = {1, 2, 3, 4, 5, 6, -3, -2, -1};
        float[] normals = {0, 0, 1, 0, 1, 0, 1, 0, 0};
        float[] uvs = {0.2F, 0.8F, 1, 0, 0.5F, 0.6F};
        List<Object[]> written = new ArrayList<>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("vertex") && args.length == 14) written.add(args);
                    else fail("捕获必须提交完整且未变换的顶点: " + method);
                    return null;
                });
        RVP_SharedOpaqueMesh.writeGeometry(new BakedVertexData(positions, normals, uvs, 3), consumer);
        assertEquals(3, written.size());
        for (int i = 0; i < 3; i++) {
            Object[] vertex = written.get(i);
            for (int j = 0; j < 3; j++) {
                assertEquals(positions[i * 3 + j], (float) vertex[j]);
                assertEquals(normals[i * 3 + j], (float) vertex[11 + j]);
            }
            for (int j = 3; j < 7; j++) assertEquals(1F, (float) vertex[j]);
            assertEquals(uvs[i * 2], (float) vertex[7]);
            assertEquals(uvs[i * 2 + 1], (float) vertex[8]);
            assertEquals(OverlayTexture.NO_OVERLAY, vertex[9]);
            assertEquals(0, vertex[10], "UV2=0 必须由每实例光照替换");
        }
    }
}
