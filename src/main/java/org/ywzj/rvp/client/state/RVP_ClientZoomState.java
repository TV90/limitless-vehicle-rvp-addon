package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.vehicle.util.VectorUtil;

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
        double base = baseFov();
        if (base <= 0) {
            return false;
        }
        return VectorUtil.fov < base * RVP_ClientConfig.getLodZoomFovRatio();
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
