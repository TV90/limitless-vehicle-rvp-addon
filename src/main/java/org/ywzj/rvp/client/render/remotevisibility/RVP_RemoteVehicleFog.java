package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.shaders.FogShape;

/** 远距载具独立绘制批次的无副作用雾参数策略。 */
public final class RVP_RemoteVehicleFog {
    private RVP_RemoteVehicleFog() {
    }

    /**
     * 普通空气环境只延长雾终点；特殊可见性环境完整保留原参数。
     * 非法远平面同样不修改原参数。
     */
    static FogParameters forRemotePass(FogParameters original, double requiredFarPlane,
                                       boolean restrictedVisibility) {
        if (restrictedVisibility || !Double.isFinite(requiredFarPlane)
                || requiredFarPlane <= 0.0D || !Float.isFinite(original.end())) {
            return original;
        }
        float remoteEnd = (float) Math.max(original.end(), requiredFarPlane);
        return new FogParameters(original.start(), remoteEnd,
                original.red(), original.green(), original.blue(), original.alpha(), original.shape());
    }

    /**
     * 一组完整的着色器雾状态。
     *
     * @param start 雾渐变起点
     * @param end 雾渐变终点
     * @param red 雾颜色红色分量
     * @param green 雾颜色绿色分量
     * @param blue 雾颜色蓝色分量
     * @param alpha 雾颜色透明度分量
     * @param shape 雾形状
     */
    record FogParameters(float start, float end, float red, float green,
                         float blue, float alpha, FogShape shape) {
    }
}
