package org.ywzj.rvp.client.compat.distanthorizons;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_DhProjectionMathTest {
    private static final float NEAR = 0.1F;
    private static final float FAR = 4096.0F;

    @Test
    void detectsForwardZAndReconstructsEndpoints() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70.0D),
                16.0F / 9.0F, NEAR, FAR);

        RVP_DhProjectionMath.ProjectionAnalysis analysis =
                RVP_DhProjectionMath.analyze(projection, NEAR, FAR).orElseThrow();

        assertFalse(analysis.reverseZ());
        assertEquals(0.0F, analysis.nearDepth(), 1.0E-4F);
        assertEquals(1.0F, analysis.emptyDepth(), 1.0E-4F);
        assertEquals(NEAR, RVP_DhProjectionMath.reconstructViewDepth(
                analysis.inverseProjection(), 0.5F, 0.5F, analysis.nearDepth()), 1.0E-4F);
        assertEquals(FAR, RVP_DhProjectionMath.reconstructViewDepth(
                analysis.inverseProjection(), 0.5F, 0.5F, analysis.emptyDepth()), 2.0F);
    }

    @Test
    void detectsReverseZAndReconstructsEndpoints() {
        Matrix4f projection = reversePerspective(NEAR, FAR);

        RVP_DhProjectionMath.ProjectionAnalysis analysis =
                RVP_DhProjectionMath.analyze(projection, NEAR, FAR).orElseThrow();

        assertTrue(analysis.reverseZ());
        assertEquals(1.0F, analysis.nearDepth(), 1.0E-4F);
        assertEquals(0.0F, analysis.emptyDepth(), 1.0E-4F);
        assertEquals(NEAR, RVP_DhProjectionMath.reconstructViewDepth(
                analysis.inverseProjection(), 0.5F, 0.5F, analysis.nearDepth()), 1.0E-4F);
        assertEquals(FAR, RVP_DhProjectionMath.reconstructViewDepth(
                analysis.inverseProjection(), 0.5F, 0.5F, analysis.emptyDepth()), 2.0F);

        RVP_DhProjectionMath.ProjectionAnalysis inferred =
                RVP_DhProjectionMath.analyzeFromProjection(projection).orElseThrow();
        assertTrue(inferred.reverseZ());
        assertEquals(NEAR, inferred.nearPlane(), 1.0E-4F);
        assertEquals(FAR, inferred.farPlane(), 2.0F);
    }

    @Test
    void recoversMatrixPlanesWhenDhReportedNearDoesNotMatchProjection() {
        float matrixNear = 7.5F;
        float dhReportedNear = 264.2934F;
        float matrixFar = 6516.696F;
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70.0D),
                16.0F / 9.0F, matrixNear, matrixFar);

        assertTrue(RVP_DhProjectionMath.analyze(
                projection, dhReportedNear, matrixFar).isEmpty());
        RVP_DhProjectionMath.ProjectionAnalysis analysis =
                RVP_DhProjectionMath.analyzeFromProjection(projection).orElseThrow();

        assertFalse(analysis.reverseZ());
        assertEquals(matrixNear, analysis.nearPlane(), 1.0E-3F);
        assertEquals(matrixFar, analysis.farPlane(), 2.0F);
        assertEquals(0.0F, analysis.nearDepth(), 1.0E-4F);
        assertEquals(1.0F, analysis.emptyDepth(), 1.0E-4F);
    }

    @Test
    void mapsDhRowsToJomlFieldsWithoutArrayConstructorAmbiguity() {
        DhApiMat4f source = new DhApiMat4f(new float[]{
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16
        });

        Matrix4f mapped = RVP_DhApi7Bridge.toJoml(source);

        assertEquals(1.0F, mapped.m00());
        assertEquals(2.0F, mapped.m10());
        assertEquals(5.0F, mapped.m01());
        assertEquals(12.0F, mapped.m32());
        assertEquals(15.0F, mapped.m23());
        assertEquals(16.0F, mapped.m33());
    }

    @Test
    void acceptsVerifiedApiSevenVersionsOnly() {
        assertFalse(RVP_DhApi7Bridge.isSupportedApiVersion(7, 0, 0));
        assertTrue(RVP_DhApi7Bridge.isSupportedApiVersion(7, 0, 1));
        assertTrue(RVP_DhApi7Bridge.isSupportedApiVersion(7, 1, 0));
        assertFalse(RVP_DhApi7Bridge.isSupportedApiVersion(6, 9, 9));
        assertFalse(RVP_DhApi7Bridge.isSupportedApiVersion(8, 0, 0));
    }

    private static Matrix4f reversePerspective(float nearPlane, float farPlane) {
        float yScale = 1.0F / (float) Math.tan(Math.toRadians(70.0D) * 0.5D);
        float denominator = farPlane - nearPlane;
        return new Matrix4f().zero()
                .m00(yScale / (16.0F / 9.0F))
                .m11(yScale)
                .m22((farPlane + nearPlane) / denominator)
                .m23(-1.0F)
                .m32((2.0F * farPlane * nearPlane) / denominator);
    }
}
