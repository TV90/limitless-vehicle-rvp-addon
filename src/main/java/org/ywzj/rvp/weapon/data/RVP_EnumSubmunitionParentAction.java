package org.ywzj.rvp.weapon.data;

import java.util.Locale;

/**
 * 子弹药释放方案执行后，母弹需要执行的动作。
 */
public enum RVP_EnumSubmunitionParentAction {

    /** 母弹继续飞行并参与后续碰撞或引爆，适用于多级火箭、脱壳穿甲弹。 */
    CONTINUE,

    /** 本释放方案完成全部预定生成后直接移除母弹，不执行母弹引爆链。 */
    DISCARD_AFTER_RELEASE,

    /** 本释放方案第一次生成载荷后立即移除母弹，不等待后续波次。 */
    DISCARD_ON_FIRST_SPAWN,

    /** 本释放方案完成释放后执行母弹完整引爆链，再移除母弹。 */
    EXPLOSION_AFTER_RELEASE;

    public static RVP_EnumSubmunitionParentAction fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONTINUE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "explosion_after_release" -> EXPLOSION_AFTER_RELEASE;
            case "discard_after_release", "discard_parent", "discard" -> DISCARD_AFTER_RELEASE;
            case "discard_on_first_spawn", "discard_immediate" -> DISCARD_ON_FIRST_SPAWN;
            default -> CONTINUE;
        };
    }
}
