package org.ywzj.rvp.client.compat.distanthorizons;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Optional;

/** DH 正/反向深度端点推导与同相机空间重建的纯数学辅助。 */
public final class RVP_DhProjectionMath {
    /** 深度端点允许的数值误差。 */
    private static final float ENDPOINT_EPSILON = 0.05F;
    /** 齐次除法允许的最小绝对 w。 */
    private static final float MIN_ABS_W = 1.0E-7F;

    private RVP_DhProjectionMath() {
    }

    /** 校验投影、near/far 与深度端点，并返回逆投影及空深度。 */
    public static Optional<ProjectionAnalysis> analyze(Matrix4f projection,
                                                       float nearPlane,
                                                       float farPlane) {
        if (projection == null || !projection.isFinite()
                || !Float.isFinite(nearPlane) || !Float.isFinite(farPlane)
                || nearPlane <= 0.0F || farPlane <= nearPlane) {
            return Optional.empty();
        }
        float nearDepth = projectViewZToDepth(projection, -nearPlane);
        float farDepth = projectViewZToDepth(projection, -farPlane);
        if (!Float.isFinite(nearDepth) || !Float.isFinite(farDepth)
                || Math.abs(nearDepth - farDepth) < 0.5F) {
            return Optional.empty();
        }
        boolean forward = closeTo(nearDepth, 0.0F) && closeTo(farDepth, 1.0F);
        boolean reverse = closeTo(nearDepth, 1.0F) && closeTo(farDepth, 0.0F);
        if (!forward && !reverse) {
            return Optional.empty();
        }
        Matrix4f inverse = new Matrix4f(projection);
        if (!inverse.invert().isFinite()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectionAnalysis(inverse, nearDepth, farDepth, reverse,
                nearPlane, farPlane));
    }

    /**
     * 直接从投影矩阵的两个纹理深度端点恢复真实 near/far。
     *
     * <p>DH 3.2.0-b 的事件 {@code nearClipPlane} 是过度绘制裁剪距离，而
     * {@code dhProjectionMatrix} 在普通高度下会把实际 near 额外限制到 7.5 格；
     * 因此 DH 深度分析不得再假定事件字段就是矩阵端点。</p>
     */
    public static Optional<ProjectionAnalysis> analyzeFromProjection(Matrix4f projection) {
        if (projection == null || !projection.isFinite()) {
            return Optional.empty();
        }
        Matrix4f inverse = new Matrix4f(projection);
        if (!inverse.invert().isFinite()) {
            return Optional.empty();
        }

        float depthZeroDistance = reconstructViewDepth(inverse, 0.5F, 0.5F, 0.0F);
        float depthOneDistance = reconstructViewDepth(inverse, 0.5F, 0.5F, 1.0F);
        if (!validPlaneDistance(depthZeroDistance) || !validPlaneDistance(depthOneDistance)
                || Math.abs(depthZeroDistance - depthOneDistance) < MIN_ABS_W) {
            return Optional.empty();
        }

        boolean reverse = depthOneDistance < depthZeroDistance;
        float nearPlane = Math.min(depthZeroDistance, depthOneDistance);
        float farPlane = Math.max(depthZeroDistance, depthOneDistance);
        float nearDepth = reverse ? 1.0F : 0.0F;
        float emptyDepth = reverse ? 0.0F : 1.0F;
        return Optional.of(new ProjectionAnalysis(inverse, nearDepth, emptyDepth, reverse,
                nearPlane, farPlane));
    }

    /** 把相机空间 Z 投影为 OpenGL 纹理深度。 */
    public static float projectViewZToDepth(Matrix4f projection, float viewZ) {
        float clipZ = projection.m22() * viewZ + projection.m32();
        float clipW = projection.m23() * viewZ + projection.m33();
        if (!Float.isFinite(clipZ) || !Float.isFinite(clipW) || Math.abs(clipW) < MIN_ABS_W) {
            return Float.NaN;
        }
        return clipZ / clipW * 0.5F + 0.5F;
    }

    /** 从屏幕 UV 与原始深度重建正向相机视深度，主要供 CPU/GPU 对照测试。 */
    public static float reconstructViewDepth(Matrix4f inverseProjection,
                                             float u, float v, float depth) {
        Vector4f view = new Vector4f(u * 2.0F - 1.0F, v * 2.0F - 1.0F,
                depth * 2.0F - 1.0F, 1.0F);
        inverseProjection.transform(view);
        if (!view.isFinite() || Math.abs(view.w) < MIN_ABS_W) {
            return Float.NaN;
        }
        return -(view.z / view.w);
    }

    /** 检查深度端点是否落在预期值附近。 */
    private static boolean closeTo(float value, float expected) {
        return Math.abs(value - expected) <= ENDPOINT_EPSILON;
    }

    /** 判断从逆投影恢复的裁剪面距离是否可作为正向相机视深度。 */
    private static boolean validPlaneDistance(float distance) {
        return Float.isFinite(distance) && distance > 0.0F;
    }

    /**
     * 已校验投影分析。
     *
     * @param inverseProjection 逆投影矩阵
     * @param nearDepth near 平面在纹理中的原始深度
     * @param emptyDepth far 平面对应的空深度
     * @param reverseZ 是否为 Reverse-Z
     * @param nearPlane 投影矩阵实际使用的近裁剪面，单位格
     * @param farPlane 投影矩阵实际使用的远裁剪面，单位格
     */
    public record ProjectionAnalysis(Matrix4f inverseProjection,
                                     float nearDepth,
                                     float emptyDepth,
                                     boolean reverseZ,
                                     float nearPlane,
                                     float farPlane) {
        /** 建立并返回逆投影矩阵防御性副本。 */
        public ProjectionAnalysis {
            inverseProjection = new Matrix4f(inverseProjection);
        }

        /** 返回逆投影矩阵副本，防止调用方修改分析结果。 */
        @Override
        public Matrix4f inverseProjection() {
            return new Matrix4f(inverseProjection);
        }
    }
}
