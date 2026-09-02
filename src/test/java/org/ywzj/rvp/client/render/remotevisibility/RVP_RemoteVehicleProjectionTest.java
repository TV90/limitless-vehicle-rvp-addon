package org.ywzj.rvp.client.render.remotevisibility;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.FarPlaneDemand;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.ProjectionPlan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RemoteVehicleProjectionTest {
    @Test
    void currentFarPlaneIsNeverShortened() {
        Matrix4f projection = perspective(0.05F, 2_048.0F);
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(projection,
                List.of(new FarPlaneDemand(800.0D, 20.0D))).orElseThrow();

        assertFalse(plan.extended());
        assertEquals(plan.worldFarPlane(), plan.requiredFarPlane(), 0.01D);
        assertMatrixEquals(projection, plan.projection(), 0.0F);
    }

    @Test
    void farthestCandidateIncludesRadiusAndSafetyMargin() {
        Matrix4f projection = perspective(0.05F, 1_024.0F);
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(projection, List.of(
                new FarPlaneDemand(1_200.0D, 10.0D),
                new FarPlaneDemand(1_800.0D, 30.0D))).orElseThrow();

        assertTrue(plan.extended());
        assertEquals(1_800.0D + 30.0D + RVP_RemoteVehicleProjection.SAFETY_MARGIN,
                plan.requiredFarPlane(), 0.01D);
    }

    @Test
    void invalidDemandsAreIgnoredAndResultIsCapped() {
        double currentFar = 1_024.0D;
        double invalidOnly = RVP_RemoteVehicleProjection.calculateRequiredFarPlane(currentFar,
                List.of(new FarPlaneDemand(Double.NaN, 1.0D),
                        new FarPlaneDemand(Double.POSITIVE_INFINITY, 1.0D),
                        new FarPlaneDemand(2_000.0D, -1.0D),
                        new FarPlaneDemand(2_000.0D,
                                RVP_RemoteVehicleProjection.MAX_CULL_RADIUS + 1.0D)));
        double capped = RVP_RemoteVehicleProjection.calculateRequiredFarPlane(currentFar,
                List.of(new FarPlaneDemand(Double.MAX_VALUE, 1.0D)));

        assertEquals(currentFar, invalidOnly, 0.0D);
        assertEquals(RVP_RemoteVehicleProjection.MAX_FAR_PLANE, capped, 0.0D);
    }

    @Test
    void extensionPreservesLensAndNearPlaneTerms() {
        Matrix4f projection = perspective(0.125F, 1_000.0F)
                .m20(0.12F)
                .m21(-0.08F);
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(projection,
                List.of(new FarPlaneDemand(3_000.0D, 25.0D))).orElseThrow();
        Matrix4f extended = plan.projection();

        assertEquals(0.125D, plan.nearPlane(), 1.0E-4D);
        assertEquals(projection.m00(), extended.m00(), 0.0F);
        assertEquals(projection.m11(), extended.m11(), 0.0F);
        assertEquals(projection.m20(), extended.m20(), 0.0F);
        assertEquals(projection.m21(), extended.m21(), 0.0F);
        assertEquals(projection.m23(), extended.m23(), 0.0F);
        assertEquals(projection.m33(), extended.m33(), 0.0F);
    }

    @Test
    void matchingFrustumKeepsSideBackAndNewFarPlaneCulling() {
        Matrix4f original = perspective(0.05F, 1_000.0F);
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(original,
                List.of(new FarPlaneDemand(2_000.0D, 10.0D))).orElseThrow();
        Frustum oldFrustum = new Frustum(new Matrix4f(), original);
        Frustum remoteFrustum = new Frustum(new Matrix4f(), plan.projection());
        oldFrustum.prepare(0.0D, 0.0D, 0.0D);
        remoteFrustum.prepare(0.0D, 0.0D, 0.0D);

        AABB beyondOldFar = AABB.ofSize(new Vec3(0.0D, 0.0D, -1_500.0D), 4.0D, 4.0D, 4.0D);
        AABB outsideSide = AABB.ofSize(new Vec3(5_000.0D, 0.0D, -1_500.0D), 4.0D, 4.0D, 4.0D);
        AABB behindCamera = AABB.ofSize(new Vec3(0.0D, 0.0D, 100.0D), 4.0D, 4.0D, 4.0D);
        AABB beyondDynamicFar = AABB.ofSize(new Vec3(0.0D, 0.0D, -2_100.0D), 4.0D, 4.0D, 4.0D);

        assertFalse(oldFrustum.isVisible(beyondOldFar));
        assertTrue(remoteFrustum.isVisible(beyondOldFar));
        assertFalse(remoteFrustum.isVisible(outsideSide));
        assertFalse(remoteFrustum.isVisible(behindCamera));
        assertFalse(remoteFrustum.isVisible(beyondDynamicFar));
    }

    @Test
    void offscreenProjectionRaisesNearAndCoversExtremeCandidateAfterFloatQuantization() {
        Matrix4f original = perspective(0.05F, 1_024.0F);
        double candidateDistance = 65_536.0D;
        double cullRadius = 16.0D;
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(original,
                List.of(new FarPlaneDemand(candidateDistance, cullRadius,
                        candidateDistance - cullRadius))).orElseThrow();

        assertEquals(RVP_RemoteVehicleProjection.OFFSCREEN_MAX_NEAR_PLANE,
                plan.offscreenNearPlane(), 0.01D);
        assertTrue(plan.offscreenFarPlane() >= plan.requiredFarPlane());
        assertTrue(plan.offscreenFarPlane() >= candidateDistance + cullRadius);
        Frustum offscreenFrustum = new Frustum(new Matrix4f(), plan.offscreenProjection());
        offscreenFrustum.prepare(0.0D, 0.0D, 0.0D);
        assertTrue(offscreenFrustum.isVisible(AABB.ofSize(
                new Vec3(0.0D, 0.0D, -candidateDistance),
                cullRadius * 2.0D, cullRadius * 2.0D, cullRadius * 2.0D)));
    }

    @Test
    void offscreenProjectionKeepsVehicleScaleDepthSeparationAtMaximumDistance() {
        Matrix4f original = perspective(0.05F, 1_024.0F);
        double candidateDistance = 65_536.0D;
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(original,
                List.of(new FarPlaneDemand(candidateDistance, 16.0D,
                        candidateDistance - 16.0D))).orElseThrow();

        float nearFaceDepth = projectDepth(plan.offscreenProjection(),
                (float) -(candidateDistance - 8.0D));
        float farFaceDepth = projectDepth(plan.offscreenProjection(),
                (float) -(candidateDistance + 8.0D));

        assertTrue(Float.isFinite(nearFaceDepth));
        assertTrue(Float.isFinite(farFaceDepth));
        assertTrue(nearFaceDepth < farFaceDepth);
        assertTrue(Float.floatToRawIntBits(farFaceDepth)
                - Float.floatToRawIntBits(nearFaceDepth) >= 4);
    }

    @Test
    void offscreenActualFarCoversRepresentativeLongRangeBands() {
        Matrix4f original = perspective(0.05F, 1_024.0F);
        for (double candidateDistance : List.of(
                4_096.0D, 8_192.0D, 16_384.0D, 32_768.0D, 65_536.0D)) {
            ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(original,
                    List.of(new FarPlaneDemand(candidateDistance, 16.0D,
                            candidateDistance - 16.0D))).orElseThrow();

            assertTrue(plan.offscreenFarPlane() >= plan.requiredFarPlane(),
                    "distance=" + candidateDistance);
        }
    }

    @Test
    void offscreenNearLeavesMarginBeforeNearestCandidateFront() {
        Matrix4f original = perspective(0.05F, 1_024.0F);
        double nearestFrontDepth = 180.0D;
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(original,
                List.of(new FarPlaneDemand(800.0D, 10.0D, nearestFrontDepth))).orElseThrow();

        assertEquals(nearestFrontDepth - RVP_RemoteVehicleProjection.OFFSCREEN_NEAR_MARGIN,
                plan.offscreenNearPlane(), 0.01D);
    }

    @Test
    void orthographicOrNonFiniteProjectionIsRejected() {
        assertTrue(RVP_RemoteVehicleProjection.plan(
                new Matrix4f().ortho(-1.0F, 1.0F, -1.0F, 1.0F, 0.05F, 100.0F),
                List.of()).isEmpty());
        Matrix4f nonFinite = perspective(0.05F, 1_000.0F).m00(Float.NaN);
        assertTrue(RVP_RemoteVehicleProjection.plan(nonFinite, List.of()).isEmpty());
    }

    /** 创建具有固定动态 FOV 和宽高比的标准透视矩阵。 */
    private static Matrix4f perspective(float nearPlane, float farPlane) {
        return new Matrix4f().perspective((float) Math.toRadians(70.0D), 16.0F / 9.0F,
                nearPlane, farPlane);
    }

    /** 使用最终 float 投影矩阵把相机视空间 Z 转换到纹理深度。 */
    private static float projectDepth(Matrix4f projection, float viewZ) {
        float clipZ = projection.m22() * viewZ + projection.m32();
        float clipW = projection.m23() * viewZ + projection.m33();
        return clipZ / clipW * 0.5F + 0.5F;
    }

    /** 比较矩阵全部 16 个元素。 */
    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual, float delta) {
        float[] expectedValues = expected.get(new float[16]);
        float[] actualValues = actual.get(new float[16]);
        for (int index = 0; index < expectedValues.length; index++) {
            assertEquals(expectedValues[index], actualValues[index], delta, "matrix index " + index);
        }
    }
}
