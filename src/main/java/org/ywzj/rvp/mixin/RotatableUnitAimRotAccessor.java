package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.vehicle.vehicle.part.RotatableUnit;

/**
 * 读取炮塔「目标角」（{@code xAimRot/yAimRot}）。
 *
 * <p>本体 {@code WeaponUnit.updateWorldAimVec()} 的实现是
 * {@code worldAimVec = worldVec(xAimRot, yAimRot)}，即目标角对应的世界方向 = <b>意图方向</b>；
 * 而 {@code worldVec()}（无参）用 {@code xRot/yRot}（经 {@code Mth.approach} 限速后的<b>实际</b>方向）。
 * 本体的「圈先走、炮塔准星追」正是这两层之差。</p>
 *
 * <p>RVP 需要意图方向来驱动本体瞄准圈（适配 {@code rvp_sight_fire_disguise} 时仍保留层次），
 * 但 {@code xAimRot/yAimRot} 是 {@code protected}、{@code worldAimVec} 是 {@code private} 且无 getter，
 * 无公共 API 可取，故按 Accessor 兜底（先例：本项目 radar/HUD 相关已用同类兜底）。</p>
 */
@Mixin(value = RotatableUnit.class, remap = false)
public interface RotatableUnitAimRotAccessor {

    @Accessor("xAimRot")
    float ywzj_rvp$getXAimRot();

    @Accessor("yAimRot")
    float ywzj_rvp$getYAimRot();
}
