package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Optional;

/**
 * 武器级 {@code parent_weapon_unit_aim} 覆盖（弹着点锚定方向）：
 * {@code WeaponUnit.currentWeaponHitPosition} 中按"当前武器是否配置
 * {@code parent_weapon_unit_aim_override}"决定弹着点预测锚定到母武器站还是自身挂架；
 * 未配置回退所属武器站静态值。
 *
 * <p>执行端说明：{@code currentWeaponHitPosition} 仅由客户端
 * {@code LocalVehiclePlayer.tickAim → tickHit} 驱动，本 mixin 放公共数组仅因目标类
 * WeaponUnit 已有既有公共 mixin（改脏状态不变）；handler 只引用公共类，无新增带毒向量。</p>
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitParentAimOverrideMixin {

    /**
     * 拦截对所属武器站 {@code isParentWeaponUnitAim()} 的查询：以发起方（操作武器站，
     * 即 mixin 目标实例）的当前 RVP 武器配置优先。
     */
    @Redirect(method = "currentWeaponHitPosition", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;isParentWeaponUnitAim()Z"))
    private boolean rvp$parentAimOverrideForHitPos(WeaponUnit owningUnit) {
        Boolean override = rvp$resolveOverrideFromCurrentSelection((WeaponUnit) (Object) this);
        return override != null ? override : owningUnit.isParentWeaponUnitAim();
    }

    /** 取目标实例当前选中的具体武器（解包 Agent/Multi）的覆盖配置。 */
    @Unique
    private static Boolean rvp$resolveOverrideFromCurrentSelection(WeaponUnit operatedUnit) {
        return RVP_WeaponResolveHelper.resolveParentWeaponUnitAimOverride(
                RVP_WeaponResolveHelper.currentPrimary(operatedUnit));
    }
}
