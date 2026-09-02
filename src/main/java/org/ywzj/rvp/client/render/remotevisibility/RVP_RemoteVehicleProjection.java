package org.ywzj.rvp.client.render.remotevisibility;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

/** 远距载具独立绘制批次使用的透视远平面计算与矩阵构造工具。 */
public final class RVP_RemoteVehicleProjection {
    /** 服务端远距载具配置允许的最大距离，单位格。 */
    static final double MAX_AUTHORIZED_DISTANCE = 65_536.0D;
    /** 单个候选允许参与远平面计算的最大包围半径，单位格。 */
    static final double MAX_CULL_RADIUS = 1_024.0D;
    /** 吸收插值、浮点计算与包围盒边缘误差的安全余量，单位格。 */
    static final double SAFETY_MARGIN = 16.0D;
    /** 动态远平面的硬上限，包含服务端距离、包围半径与安全余量。 */
    static final double MAX_FAR_PLANE =
            MAX_AUTHORIZED_DISTANCE + MAX_CULL_RADIUS + SAFETY_MARGIN;
    /** 离屏远距投影允许使用的最大近裁剪面，单位格。 */
    static final double OFFSCREEN_MAX_NEAR_PLANE = 256.0D;
    /** 离屏近裁剪面与最近候选包围球前缘之间保留的安全余量，单位格。 */
    static final double OFFSCREEN_NEAR_MARGIN = 16.0D;
    /** 修正 float 投影系数量化时允许临时增加的最大远平面余量，单位格。 */
    private static final double OFFSCREEN_FAR_HEADROOM = 4_096.0D;
    /** 迭代修正离屏实际远平面的最大次数。 */
    private static final int OFFSCREEN_FAR_REFINEMENT_STEPS = 8;
    /** 判断标准 OpenGL 透视矩阵固定项时使用的误差。 */
    private static final float PERSPECTIVE_EPSILON = 1.0E-3F;
    /** 判断是否确实需要延长远平面时使用的最小变化，单位格。 */
    private static final double EXTENSION_EPSILON = 0.5D;

    private RVP_RemoteVehicleProjection() {
    }

    /**
     * 从当前事件投影和本帧预候选建立远距绘制计划。
     * 非标准、非有限或无法恢复近远平面的投影会被安全拒绝。
     */
    public static Optional<ProjectionPlan> plan(Matrix4f currentProjection,
                                                List<FarPlaneDemand> demands) {
        Optional<DepthRange> depthRange = readDepthRange(currentProjection);
        if (depthRange.isEmpty()) {
            return Optional.empty();
        }
        DepthRange range = depthRange.get();
        double requiredFarPlane = calculateRequiredFarPlane(range.farPlane(), demands);
        boolean extended = requiredFarPlane > range.farPlane() + EXTENSION_EPSILON;
        Matrix4f remoteProjection = extended
                ? extendFarPlane(currentProjection, range.nearPlane(), requiredFarPlane)
                : new Matrix4f(currentProjection);
        if (!remoteProjection.isFinite()) {
            return Optional.empty();
        }
        double offscreenNearPlane = calculateOffscreenNearPlane(range.nearPlane(), demands);
        Optional<DepthRangeProjection> offscreenProjection = buildCoveringProjection(
                currentProjection, offscreenNearPlane, requiredFarPlane);
        if (offscreenProjection.isEmpty()) {
            return Optional.empty();
        }
        DepthRangeProjection offscreen = offscreenProjection.get();
        return Optional.of(new ProjectionPlan(remoteProjection, range.nearPlane(),
                range.farPlane(), requiredFarPlane, extended,
                offscreen.projection(), offscreen.nearPlane(), offscreen.farPlane()));
    }

    /**
     * 计算覆盖全部合法候选的远平面；非法输入不参与计算，结果不会缩短当前远平面。
     */
    static double calculateRequiredFarPlane(double currentFarPlane,
                                            List<FarPlaneDemand> demands) {
        if (!Double.isFinite(currentFarPlane) || currentFarPlane <= 0.0D) {
            return Double.NaN;
        }
        double required = Math.min(currentFarPlane, MAX_FAR_PLANE);
        for (FarPlaneDemand demand : demands) {
            if (demand == null
                    || !Double.isFinite(demand.cameraDistance())
                    || !Double.isFinite(demand.cullRadius())
                    || demand.cameraDistance() < 0.0D
                    || demand.cullRadius() < 0.0D
                    || demand.cullRadius() > MAX_CULL_RADIUS) {
                continue;
            }
            double candidateRequired = demand.cameraDistance()
                    + demand.cullRadius() + SAFETY_MARGIN;
            if (Double.isFinite(candidateRequired)) {
                required = Math.max(required, Math.min(candidateRequired, MAX_FAR_PLANE));
            }
        }
        return required;
    }

    /** 保留原矩阵的 FOV、宽高比、偏移和近裁剪面，只替换远深度系数。 */
    static Matrix4f extendFarPlane(Matrix4f currentProjection, double nearPlane,
                                   double requiredFarPlane) {
        return replaceDepthRange(currentProjection, nearPlane, requiredFarPlane);
    }

    /** 保留镜头横纵项，只替换标准 Forward-Z 透视矩阵的近远裁剪系数。 */
    private static Matrix4f replaceDepthRange(Matrix4f currentProjection, double nearPlane,
                                              double farPlane) {
        double denominator = farPlane - nearPlane;
        double m22 = -(farPlane + nearPlane) / denominator;
        double m32 = -(2.0D * farPlane * nearPlane) / denominator;
        return new Matrix4f(currentProjection)
                .m22((float) m22)
                .m32((float) m32);
    }

    /**
     * 按候选包围球的相机视深度前缘抬高离屏 near，避免远端 Forward-Z 深度全部坍缩到 1。
     */
    static double calculateOffscreenNearPlane(double originalNearPlane,
                                              List<FarPlaneDemand> demands) {
        double nearestFrontDepth = Double.POSITIVE_INFINITY;
        for (FarPlaneDemand demand : demands) {
            if (demand == null || !Double.isFinite(demand.minimumViewDepth())) {
                continue;
            }
            nearestFrontDepth = Math.min(nearestFrontDepth, demand.minimumViewDepth());
        }
        if (!Double.isFinite(nearestFrontDepth)) {
            return originalNearPlane;
        }
        double safeNearPlane = nearestFrontDepth - OFFSCREEN_NEAR_MARGIN;
        return Math.max(originalNearPlane, Math.min(OFFSCREEN_MAX_NEAR_PLANE, safeNearPlane));
    }

    /**
     * 构造离屏投影并从最终 float 矩阵反解实际裁剪面；实际 far 不足时增加编码 far 后重试。
     */
    private static Optional<DepthRangeProjection> buildCoveringProjection(
            Matrix4f currentProjection, double nearPlane, double requiredFarPlane) {
        if (!Double.isFinite(nearPlane) || !Double.isFinite(requiredFarPlane)
                || nearPlane <= 0.0D || requiredFarPlane <= nearPlane) {
            return Optional.empty();
        }
        double encodedFarPlane = requiredFarPlane;
        double maximumEncodedFarPlane = MAX_FAR_PLANE + OFFSCREEN_FAR_HEADROOM;
        for (int attempt = 0; attempt < OFFSCREEN_FAR_REFINEMENT_STEPS; attempt++) {
            Matrix4f projection = replaceDepthRange(currentProjection, nearPlane, encodedFarPlane);
            Optional<DepthRange> actualRange = readDepthRange(
                    projection, maximumEncodedFarPlane + OFFSCREEN_FAR_HEADROOM);
            if (actualRange.isPresent()) {
                DepthRange actual = actualRange.get();
                if (actual.farPlane() >= requiredFarPlane) {
                    return Optional.of(new DepthRangeProjection(
                            projection, actual.nearPlane(), actual.farPlane()));
                }
                double deficit = requiredFarPlane - actual.farPlane();
                encodedFarPlane += Math.max(1.0D, deficit * 2.0D);
            } else {
                encodedFarPlane += Math.max(1.0D, encodedFarPlane * 1.0E-5D);
            }
            if (encodedFarPlane > maximumEncodedFarPlane) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 从 JOML/OpenGL 透视矩阵恢复近、远裁剪面。 */
    private static Optional<DepthRange> readDepthRange(Matrix4f projection) {
        return readDepthRange(projection, MAX_FAR_PLANE);
    }

    /** 从投影矩阵恢复裁剪面，并按调用方给定上限拒绝异常矩阵。 */
    private static Optional<DepthRange> readDepthRange(Matrix4f projection,
                                                       double maximumFarPlane) {
        if (projection == null || !projection.isFinite()
                || Math.abs(projection.m23() + 1.0F) > PERSPECTIVE_EPSILON
                || Math.abs(projection.m33()) > PERSPECTIVE_EPSILON
                || Math.abs(projection.m00()) <= PERSPECTIVE_EPSILON
                || Math.abs(projection.m11()) <= PERSPECTIVE_EPSILON) {
            return Optional.empty();
        }
        double nearPlane = projection.m32() / (projection.m22() - 1.0D);
        double farPlane = projection.m32() / (projection.m22() + 1.0D);
        if (!Double.isFinite(nearPlane) || !Double.isFinite(farPlane)
                || nearPlane <= 0.0D || farPlane <= nearPlane
                || farPlane > maximumFarPlane) {
            return Optional.empty();
        }
        return Optional.of(new DepthRange(nearPlane, farPlane));
    }

    /**
     * 单个预候选对远平面的需求。
     *
     * @param cameraDistance 相机到候选中心的三维距离，单位格
     * @param cullRadius 候选渲染包围盒的外接球半径，单位格
     * @param minimumViewDepth 候选包围球最靠近相机一侧的正向视深度，单位格
     */
    public record FarPlaneDemand(double cameraDistance, double cullRadius,
                                 double minimumViewDepth) {
        /** 兼容不需要离屏 near 规划的纯远平面调用。 */
        public FarPlaneDemand(double cameraDistance, double cullRadius) {
            this(cameraDistance, cullRadius, Double.NaN);
        }
    }

    /**
     * 本帧远距载具投影计划。
     *
     * @param projection 实际绘制和专用 Frustum 共用的投影矩阵
     * @param nearPlane 从原投影恢复的近裁剪面，单位格
     * @param worldFarPlane 原世界投影远平面，单位格
     * @param requiredFarPlane 本帧远距载具批次需要的远平面，单位格
     * @param extended 是否需要切换到扩展投影
     * @param offscreenProjection DH/晚期回退离屏绘制使用的高精度远距投影
     * @param offscreenNearPlane 从最终 float 离屏矩阵恢复的实际近裁剪面，单位格
     * @param offscreenFarPlane 从最终 float 离屏矩阵恢复的实际远裁剪面，单位格
     */
    public record ProjectionPlan(Matrix4f projection, double nearPlane,
                                  double worldFarPlane, double requiredFarPlane,
                                  boolean extended, Matrix4f offscreenProjection,
                                  double offscreenNearPlane, double offscreenFarPlane) {
        /** 防止调用方修改计划持有的两张投影矩阵。 */
        public ProjectionPlan {
            projection = new Matrix4f(projection);
            offscreenProjection = new Matrix4f(offscreenProjection);
        }

        /** 返回世界直绘投影副本。 */
        @Override
        public Matrix4f projection() {
            return new Matrix4f(projection);
        }

        /** 返回离屏远距投影副本。 */
        @Override
        public Matrix4f offscreenProjection() {
            return new Matrix4f(offscreenProjection);
        }
    }

    /**
     * 从原投影恢复出的深度范围。
     *
     * @param nearPlane 近裁剪面，单位格
     * @param farPlane 远裁剪面，单位格
     */
    private record DepthRange(double nearPlane, double farPlane) {
    }

    /** 最终 float 矩阵及从该矩阵反解的实际裁剪面。 */
    private record DepthRangeProjection(Matrix4f projection, double nearPlane,
                                        double farPlane) {
    }
}
