package org.ywzj.rvp.client.state;

import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

/**
 * 客户端盘旋能力判定，供动作条决定是否显示盘旋卡片。
 * <p>只看当前载具自身：固定翼 + 载具 JSON 配了 {@code rvp_loiter_enabled}。
 * 地面母车即使携带可盘旋子机也不显示——F 键服务端只接受当前载具自身盘旋，
 * 避免出现"有图标但按键无效果"的误导。</p>
 */
public final class RVP_ClientLoiterSupport {

    private RVP_ClientLoiterSupport() {}

    /** 当前载具自身是否具备可执行的自动盘旋功能。 */
    public static boolean applicable(AbstractVehicle vehicle) {
        // 与 RVP_UavLoiterManager.supportsLoiter 同语义：仅固定翼接入盘旋飞控
        return vehicle instanceof FixedWingVehicle
                && RVP_LoiterConfigCache.get(vehicle.getVehicleId()).isConfigured();
    }
}
