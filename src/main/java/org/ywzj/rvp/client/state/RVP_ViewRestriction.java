package org.ywzj.rvp.client.state;

import org.ywzj.rvp.ext.PartUnitDataExt;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;

/**
 * 客户端「座位视角限制」判定（数据驱动，零硬编码载具/座位名）。
 *
 * <p>判定来源：当前座位 partUnit 的 RVP 运行时扩展标志 {@code rvp_no_cockpit_view}
 * （由 {@code PartUnitPojoMixin}（JSON 层）→ {@code PartUnitDataMixin}（运行时层）注入）。
 * 消费方为 {@code LocalVehiclePlayerViewTypeRestrictMixin}：被标记座位上
 * {@code switchViewType} 的 OPERATOR 写入被跳过，视角循环仅剩 THIRD_PERSON/SCOPE。
 * 换座位时 {@code LocalVehiclePlayer.seat} 经 toSeat 即时更新，判定随座位自动生效/失效。</p>
 */
public final class RVP_ViewRestriction {

    private RVP_ViewRestriction() {
    }

    /**
     * 当前驾驶/乘坐的座位是否被标记为「无座舱视角」。
     *
     * @return true = 该座位视角循环不允许进入 OPERATOR（座舱第一人称）；
     *         未上载具、座位/部件缺失或未标记时返回 false（行为与本体完全一致）
     */
    public static boolean isCockpitViewBlocked() {
        // LocalVehiclePlayer 为 @OnlyIn(CLIENT) 单例，本类仅被 client 数组 Mixin 在客户端调用
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp == null || lvp.seat == null || lvp.seat.partUnit == null) {
            return false;
        }
        PartUnit<?> partUnit = lvp.seat.partUnit;
        // 调用本体 PartUnit.getData() 取运行时部件数据，RVP 标志经 PartUnitDataExt 读取
        PartUnitData data = partUnit.getData();
        return data instanceof PartUnitDataExt ext && ext.ywzj_rvp$isNoCockpitView();
    }
}
