package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 客户端缩放状态检测（LOD 距离过滤条件）。
 * <p>
 * 以每帧捕获的最终 FOV（{@link VectorUtil#fov}，覆盖 ywzj_vehicle 缩放、
 * RVP 武器缩放、原版望远镜缩放）与 FOV 设置基线比较，判断玩家是否处于缩放状态。
 * <p>
 * 缩放中视场内的载具较少、渲染压力低，可让 LOD 更晚（更远）才替换模型，
 * 因此 {@link #lodDistanceMultiplier()} 返回大于 1 的系数放大 LOD 距离阈值。
 * <p>
 * 开关、FOV 判定比例与放大系数均可在 {@code ywzj_rvp-client.toml} 中配置。
 */
public final class RVP_ClientZoomState {

    private RVP_ClientZoomState() {}

    public static boolean isZoomed() {
        if (!RVP_ClientConfig.isLodZoomEnabled()) {
            return false;
        }
        return isFovZoomed();
    }

    /**
     * 返回本帧是否应为超视距载具禁用 Billboard，恢复普通 LOD 到基础高模路径。
     * <p>
     * 此判定只消费客户端本地 common 开关，不改变服务端同步的 Billboard 策略。
     */
    public static boolean shouldPreferRemoteVehicleModelRendering() {
        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        return shouldPreferRemoteVehicleModelRendering(
                RVP_CommonConfig.shouldPreferRemoteVehicleModelRenderingInScopeZoom(),
                localVehiclePlayer != null && localVehiclePlayer.vehicle != null,
                localVehiclePlayer != null
                        && localVehiclePlayer.viewType == LocalVehiclePlayer.ViewType.SCOPE,
                isFovZoomed());
    }

    /** 返回最终渲染 FOV 是否达到既有缩放阈值，不受 {@code lodZoomEnabled} 开关影响。 */
    private static boolean isFovZoomed() {
        return isFovZoomed(VectorUtil.fov, baseFov(), RVP_ClientConfig.getLodZoomFovRatio());
    }

    /** 可单元测试的最终 FOV 缩放判定。 */
    static boolean isFovZoomed(double renderedFov, double baseFov, double fovRatio) {
        return Double.isFinite(renderedFov)
                && Double.isFinite(baseFov)
                && Double.isFinite(fovRatio)
                && renderedFov > 0.0D
                && baseFov > 0.0D
                && fovRatio > 0.0D
                && renderedFov < baseFov * fovRatio;
    }

    /** 可单元测试的观瞄缩放模型优先条件组合。 */
    static boolean shouldPreferRemoteVehicleModelRendering(boolean configEnabled,
                                                           boolean usingVehicle,
                                                           boolean scopeView,
                                                           boolean fovZoomed) {
        return configEnabled && usingVehicle && scopeView && fovZoomed;
    }

    /** LOD 距离阈值应乘的系数：缩放中放大阈值（更远才 LOD），否则 1.0。 */
    public static double lodDistanceMultiplier() {
        return isZoomed() ? RVP_ClientConfig.getLodZoomDistanceMultiplier() : 1.0;
    }

    private static double baseFov() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options != null ? mc.options.fov().get() : 70.0;
    }
}
