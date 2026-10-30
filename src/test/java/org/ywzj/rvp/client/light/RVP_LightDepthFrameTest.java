package org.ywzj.rvp.client.light;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RVP_LightDepthFrameTest {
    /** DH 真实测试基线的 near=7.5，事件给出的裁剪距离不参与重建。 */
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.6F, 7.5F, 4096);
    /** 不引用 Minecraft 的世界身份，验证纯渲染帧生命周期。 */
    private final Object world = new Object();

    @Test
    void noDhEventOnNextRenderFrameCannotReuseOldDepthEvenWhenGameIsPaused() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        frame.beginFrame(world);
        frame.publish(world, 17, projection, new Matrix4f());
        assertNotNull(frame.current(world));
        frame.beginFrame(world);
        assertNull(frame.current(world));
    }

    @Test
    void worldIdentityPreventsCrossDimensionAndReconnectReuse() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        frame.beginFrame(world);
        frame.publish(world, 17, projection, new Matrix4f());
        Object replacement = new Object();
        assertNull(frame.current(replacement));
        frame.beginFrame(replacement);
        frame.publish(world, 18, projection, new Matrix4f());
        assertNull(frame.current(replacement));
    }

    @Test
    void publishedAndReturnedMatricesAreDefensiveCopies() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        Matrix4f view = new Matrix4f().rotateY(0.7F);
        Matrix4f expectedView = new Matrix4f(view);
        Matrix4f expectedProjection = new Matrix4f(projection);
        frame.beginFrame(world);
        frame.publish(world, 17, projection, view);
        view.identity(); projection.identity();
        RVP_LightDepthFrame.Snapshot captured = frame.current(world);
        assertEquals(expectedView, captured.view());
        assertEquals(expectedProjection, captured.projection());
        captured.view().zero(); captured.projection().zero();
        assertEquals(expectedView, frame.current(world).view());
        assertEquals(expectedProjection, frame.current(world).projection());
    }

    @Test
    void finalTransparentPassReplacesOpaqueDepthWithoutDependingOnVehiclePlans() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        frame.beginFrame(world);
        frame.publish(world, 17, projection, new Matrix4f());
        long generation = frame.current(world).frameId();
        frame.publish(world, 28, projection, new Matrix4f());
        assertEquals(28, frame.current(world).texture());
        assertEquals(generation, frame.current(world).frameId());
    }

    @Test
    void invalidDepthInputsNeverBecomeCurrent() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        frame.beginFrame(world);
        frame.publish(world, 0, projection, new Matrix4f());
        frame.publish(world, 17, new Matrix4f().zero(), new Matrix4f());
        frame.publish(world, 17, projection, new Matrix4f().m00(Float.NaN));
        frame.publish(world, 17, projection, new Matrix4f().zero());
        assertNull(frame.current(world));
    }

    @Test
    void reverseDepthProjectionIsAcceptedWithItsOwnActualEndpoints() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        Matrix4f reverse = new Matrix4f(projection).m22(-projection.m22()).m32(-projection.m32());
        frame.beginFrame(world);
        frame.publish(world, 17, reverse, new Matrix4f());
        assertEquals(reverse, frame.current(world).projection());
    }

    @Test
    void resourceReloadClearsReferencesAndRejectsCallbacksUntilNextFrame() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        frame.beginFrame(world);
        frame.publish(world, 17, projection, new Matrix4f());
        frame.clear();
        frame.publish(world, 18, projection, new Matrix4f());
        assertNull(frame.current(world));
        frame.beginFrame(world);
        frame.publish(world, 19, projection, new Matrix4f());
        assertEquals(19, frame.current(world).texture());
    }

    @Test
    void irisTextureNeedsFreshDhRenderEvidenceEvenWhenTextureIdRemainsValid() {
        RVP_LightDepthFrame frame = new RVP_LightDepthFrame();
        frame.beginFrame(world);
        assertFalse(frame.wasRendered(world));
        frame.markRendered(world);
        assertTrue(frame.wasRendered(world));
        assertNull(frame.current(world));
        frame.beginFrame(world);
        assertFalse(frame.wasRendered(world));
        frame.markRendered(new Object());
        assertFalse(frame.wasRendered(world));
    }
}
