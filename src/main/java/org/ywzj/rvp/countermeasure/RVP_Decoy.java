package org.ywzj.rvp.countermeasure;

import org.jetbrains.annotations.NotNull;

/**
 * 干扰物接口：供制导 seeker 与雷达侧按类型统计/过滤。
 *
 * <p>热焰弹（FLARE）干扰 IR/AIR；箔条（CHAFF）干扰 SARH/ARH，并对雷达锁定制造成"计数超阈值脱锁"。</p>
 */
public interface RVP_Decoy {

    /** 干扰物类型。 */
    @NotNull
    RVP_EnumCountermeasureType rvp$decoyType();
}
