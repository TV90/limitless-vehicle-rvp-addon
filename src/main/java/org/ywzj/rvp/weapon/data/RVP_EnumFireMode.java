package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.Locale;

/**
 * 武器开火模式。JSON {@code fire_data.fire_mode} 使用枚举名（不区分大小写），见 {@link #fromString(String)}。
 */
public enum RVP_EnumFireMode {
    /** 按住开火键按 {@code shoot_interval} 连射。 */
    @SerializedName("FULL_AUTO")
    FULL_AUTO,
    /** 每次按下开火键发射一轮（松开后才能再次触发）。 */
    @SerializedName("SEMI_AUTO")
    SEMI_AUTO,
    /** 按下后锁定连射，直至松开开火键。 */
    @SerializedName("BURST")
    BURST,
    /**
     * 长按蓄力；蓄满自动发射一轮并立即重新蓄力（无需松开鼠标）。
     * 使用 {@link RVP_FireData#getChargeTime()} 作为蓄满 tick 数。
     */
    @SerializedName("CHARGE")
    CHARGE,
    /**
     * 长按提升“转速”（内部 spinTick）；转速足够后按射速连射；
     * 松开后转速缓慢下降，无需每次蓄满即可维持射速。
     */
    @SerializedName("MINIGUN")
    MINIGUN,
    /**
     * 单击开始蓄力；蓄力期间再次点击无效；蓄满后自动发射且不可打断。
     * 使用 {@link RVP_FireData#getChargeTime()} 作为蓄力时长。
     */
    @SerializedName("RAILGUN")
    RAILGUN;

    public static RVP_EnumFireMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL_AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FULL_AUTO;
        }
    }
}
