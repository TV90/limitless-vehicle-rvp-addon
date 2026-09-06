package org.ywzj.rvp.firesupport.data;

import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_Explosion;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 从本体实际武器索引提取出的、可供纯逻辑校验使用的不可变能力描述。 */
public record RVP_FireSupportResolvedWeapon(
        /** RVP 内部武器行为分类。 */ RVP_EnumWeaponKind kind,
        /** 是否依赖玩家持续操作的 HITL。 */ boolean humanInTheLoop,
        /** 是否依赖武器站/客户端持续目标点。 */ boolean operatorGuided,
        /** 是否为 HITL CLOS TV 指令制导。 */ boolean hitlClosTvGuided,
        /** 顶层弹体最大生命期，单位 Tick。 */ int lifeTicks,
        /** 单发顶层弹体最多直接展开的子实体数。 */ int directSubmunitionCount,
        /** 子载荷是否允许继续执行自己的子母弹配置。 */ boolean nestedSubmunitionEnabled,
        /** 主爆炸伤害；未启用爆炸时为 0。 */ float explosionDamage,
        /** 主爆炸半径，单位格；未启用爆炸时为 0。 */ float explosionRadius) {

    /** 从本项目 RVP 武器数据提取能力，不保留可变数据对象引用。 */
    public static RVP_FireSupportResolvedWeapon from(RVP_WeaponData data) {
        long directChildren = 0L;
        boolean nested = false;
        for (RVP_SubmunitionReleaseData release : data.getSubmunitionData().getReleases()) {
            long perEvent = 0L;
            for (RVP_SubmunitionPayloadData payload : release.getPayloads()) {
                perEvent = saturatedAdd(perEvent, payload.getCount());
                nested |= payload.isAllowSubmunition();
            }
            directChildren = saturatedAdd(directChildren,
                    saturatedMultiply(release.resolveReleaseEvents(), perEvent));
        }
        RVP_Explosion explosion = data.getExplosionData();
        boolean explodes = explosion != null && explosion.explode;
        return new RVP_FireSupportResolvedWeapon(data.getWeaponKind(), data.hasHumanInTheLoop(),
                data.isOperatorGuided(), data.isHitlClosTvGuided(), data.getLife(),
                (int) Math.min(directChildren, Integer.MAX_VALUE), nested,
                explodes ? explosion.damage : 0.0F, explodes ? explosion.radius : 0.0F);
    }

    /** 饱和加法避免恶意载具包配置在预算估算阶段发生整数回绕。 */
    private static long saturatedAdd(long left, long right) {
        if (left >= Integer.MAX_VALUE || right >= Integer.MAX_VALUE || left > Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }

    /** 饱和乘法避免 release 数量与 payload 数量相乘时溢出。 */
    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left >= Integer.MAX_VALUE || right >= Integer.MAX_VALUE || left > Integer.MAX_VALUE / right) {
            return Integer.MAX_VALUE;
        }
        return left * right;
    }
}
