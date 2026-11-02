package org.ywzj.rvp.event;

/** 直升机三态：记录已接受的模式，使程序设置与本体 Z 键翻转使用同一个状态基准。 */
enum RVP_RotaryFlightMode {
    NORMAL,
    AUTO_COLLECTIVE,
    HOVER;

    /** 本体 Z 键只翻转 hoverMode；与当前模式一致时保持，不把程序确认的悬停当作新按键。 */
    RVP_RotaryFlightMode observeHover(boolean hover) {
        if (hover == (this == HOVER)) return this;
        return switch (this) {
            case NORMAL -> AUTO_COLLECTIVE;
            case AUTO_COLLECTIVE -> HOVER;
            case HOVER -> NORMAL;
        };
    }
}
