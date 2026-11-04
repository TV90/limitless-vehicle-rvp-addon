package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.vehicle.client.gui.VehicleWeaponOverlay;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * [RVP] 武器栏（右下角弹药 HUD）对「只能用改装工具切弹」的多弹种槽的适配（2026-10-21，适配本体新版武器栏）。
 *
 * <p><b>背景</b>：本体 2026-10 重写 {@code VehicleWeaponOverlay}（卡片式面板），旧的
 * {@code renderCard} 小圆点已被 {@code buildEntries} / {@code renderWeaponReadout} 取代，
 * 原先 {@code @Redirect(renderCard → getSubWeapons)} 因 {@code require = 0} 静默失效。
 * 新 UI 只要当前武器是 {@code VehicleMultiWeapons}，就会画「弹药子列表 + [F] ↑↓」；
 * 而 LAV25 / ZBL08A 的 {@code modding_only_multi} 槽 F 键已被
 * {@code WeaponUnitMultiWeaponSwitchBlockMixin} 拦掉、只能用改装工具切换
 * ⇒ 提示与实际不符，且一个弹巢里同时列出两种弹药。</p>
 *
 * <p><b>做法</b>：在 {@code buildEntries} 解析武器时，把「平铺的 modding-only 多弹种槽」折叠为
 * {@link VehicleMultiWeapons#getSelectedWeapon()}（当前弹种）：HUD 只显示当前弹种名称与它的余弹，
 * 不再画弹药子列表与 [F] 提示。切换弹种（改装工具）后自动跟着变。</p>
 *
 * <p><b>不误伤 T90M / VT4 类分组槽</b>：其外层 {@code VehicleMultiWeapons} 的子项本身仍是
 * {@code VehicleMultiWeapons}（AP 组 / HE 组），且 {@code isGroupedSlotCarrier} 为真——
 * 两条判据任一命中即原样保留（大组间 F 切换是真实可用的）。</p>
 *
 * <p>合规：目标 {@code VehicleWeaponOverlay} 为本体纯客户端 GUI 类，本 Mixin 只注册于 client 数组；
 * 仅一处 {@code @Redirect}，无共享状态；回退方式＝从 client 数组移除本条目。</p>
 */
@Mixin(value = VehicleWeaponOverlay.class, remap = false)
public class VehicleWeaponOverlayMixin {

    @Redirect(
            method = "buildEntries",
            at = @At(value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;proxyWeapon(Lorg/ywzj/vehicle/vehicle/weapon/AbstractVehicleWeapon;)Lorg/ywzj/vehicle/vehicle/weapon/AbstractVehicleWeapon;"),
            remap = false
    )
    private AbstractVehicleWeapon<?> ywzj_rvp$collapseModdingOnlyMulti(WeaponUnit self, AbstractVehicleWeapon<?> weapon) {
        AbstractVehicleWeapon<?> resolved = self.proxyWeapon(weapon);
        return org.ywzj.rvp.vehicle.RVP_ModdingWeaponPresentation.collapse(self, resolved);
    }
}
