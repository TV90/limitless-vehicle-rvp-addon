package org.ywzj.rvp.util;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;

/**
 * 座位权限公共工具（双端安全，仅依赖本体 AbstractVehicle/LivingEntity）。
 *
 * <p>为"指定座位使用"类功能（干扰物/主动ECM 等）提供统一判定：
 * 座位索引以 {@code AbstractVehicle.seats} 的 {@code seatIndex} 为准（0 = 一号位/驾驶位）；
 * 白名单语义对齐 {@code RVP_DeployableUavConfig.isSeatAllowed}——列表为空/缺省时仅一号位可用。</p>
 */
public final class RVP_SeatAccessHelper {

    private RVP_SeatAccessHelper() {
    }

    /**
     * 查找乘员在载具上的座位索引。
     *
     * @return 座位索引（0 = 一号位）；乘员为 null 或不在该载具任何座位上时返回 {@code -1}
     *         （调用方应将 -1 视为无权限，防止伪造包越权）
     */
    public static int findSeatIndex(AbstractVehicle vehicle, @Nullable LivingEntity operator) {
        if (vehicle == null || operator == null || vehicle.seats == null) {
            return -1;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat != null && seat.passengerId != null && seat.passengerId == operator.getId()) {
                Integer index = seat.seatIndex;
                return index == null ? -1 : index;
            }
        }
        return -1;
    }

    /**
     * 座位是否在白名单内。
     *
     * @param allowedSeatIndexes 允许的座位索引列表；{@code null}/空 = 仅一号位（0）可用
     * @param seatIndex          待判定的座位索引（-1 = 不在任何座位上，一律 false）
     */
    public static boolean isSeatAllowed(@Nullable List<Integer> allowedSeatIndexes, int seatIndex) {
        if (seatIndex < 0) {
            return false;
        }
        if (allowedSeatIndexes == null || allowedSeatIndexes.isEmpty()) {
            return seatIndex == 0;
        }
        return allowedSeatIndexes.contains(seatIndex);
    }
}
