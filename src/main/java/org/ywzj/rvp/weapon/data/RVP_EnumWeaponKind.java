package org.ywzj.rvp.weapon.data;

/**
 * RVP 内部武器行为分类。
 *
 * <p>扩展包 JSON 仍然只写七个公开 {@code rvp:*} 类型；此枚举由注册器内部设置，
 * 用来选择弹体和运行时行为。</p>
 */
public enum RVP_EnumWeaponKind {
    /** 导弹类弹体，支持 TV/ARH/ARM/GPS/IR/SARH/SACLOS/MCLOS/IOG 等制导组合；TV 和 ARH 只在该类型生效。 */
    MISSILE,
    /** 火箭类弹体，通常无制导或简单直飞。 */
    ROCKET,
    /** 机枪/机炮类弹体，可用于普通弹、霰弹、鸭弹。 */
    MACHINEGUN,
    /** 炸弹类弹体，可用于重力弹、GPS 滑翔弹、集束弹。 */
    BOMB,
    /** 瞬时射线武器。 */
    LASER,
    /** 投放器/布撒器载荷。 */
    DISPENSER,
    /** 目标指示吊舱，用于写入 GPS/SACLOS 目标点。 */
    TARGETING_POD
}
