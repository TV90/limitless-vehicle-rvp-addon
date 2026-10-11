package org.ywzj.rvp.client.firecontrol;

import java.util.Locale;

/**
 * 预瞄圈火控三态（稳定器模式）位掩码，供武器站 JSON 子集配置与切换循环共用。
 *
 * <p>写法对齐 {@code RVP_TvVideoModeMask}：每位对应一个模式，{@link #ALL} 即缺省全集；
 * JSON 数组逐项 OR 成掩码，全非法或未配置回退 {@link #ALL}。</p>
 */
public final class RVP_FireControlModeMask {

    /** 自动（炮塔锁预瞄圈）。 */
    public static final int STABLE = 1;

    /** 半自动（预瞄方向叠加鼠标偏置）。 */
    public static final int SEMI_AUTO = 1 << 1;

    /** 手动（火控不驱动武器站）。 */
    public static final int OFF = 1 << 2;

    /** 缺省全集：三种模式全部开放。 */
    public static final int ALL = STABLE | SEMI_AUTO | OFF;

    private RVP_FireControlModeMask() {}

    public static int parse(String mode) {
        if (mode == null) {
            return 0;
        }
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "AUTO" -> STABLE;
            case "SEMI" -> SEMI_AUTO;
            case "MANUAL" -> OFF;
            default -> 0;
        };
    }
}
