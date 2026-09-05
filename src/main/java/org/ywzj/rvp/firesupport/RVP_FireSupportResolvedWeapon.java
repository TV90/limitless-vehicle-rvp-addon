package org.ywzj.rvp.firesupport;

import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 从本体实际武器索引提取出的、可供纯逻辑校验使用的不可变能力描述。 */
public record RVP_FireSupportResolvedWeapon(
        /** RVP 内部武器行为分类。 */ RVP_EnumWeaponKind kind,
        /** 是否依赖玩家持续操作的 HITL。 */ boolean humanInTheLoop,
        /** 是否依赖武器站/客户端持续目标点。 */ boolean operatorGuided,
        /** 是否为 HITL CLOS TV 指令制导。 */ boolean hitlClosTvGuided) {

    /** 从本项目 RVP 武器数据提取能力，不保留可变数据对象引用。 */
    public static RVP_FireSupportResolvedWeapon from(RVP_WeaponData data) {
        return new RVP_FireSupportResolvedWeapon(data.getWeaponKind(), data.hasHumanInTheLoop(),
                data.isOperatorGuided(), data.isHitlClosTvGuided());
    }
}
