package org.ywzj.rvp.vehicle;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.List;

/**
 * RVP 多弹种武器实现：在 {@link VehicleMultiWeapons} 基础上修复嵌套多弹种切换弹种后的装填行为。
 *
 * <p>载具 JSON 中 `modding_only_multi`（内层 multi）+ `merge_into_previous_slot`（合并进外层）
 * 会形成嵌套 {@link VehicleMultiWeapons}。本体 {@code VehicleMultiWeapons.cycleSubWeapon}
 * 仅对当前选中的叶子武器执行 {@code setRemainAmmo(0)} / {@code startReload()}，
 * 当选中/切出的武器是嵌套 multi 时（{@code getRemainAmmo} 委托子武器），清零与装填全部失效，
 * 表现为切换弹种后直接满弹可打。本类在切换后递归清零所有叶子武器弹药并开始装填
 * （对所有子弹种生效，与本体"切换时清零旧弹种"的行为一致）。
 */
public class RVP_VehicleMultiWeapons extends VehicleMultiWeapons {

    public RVP_VehicleMultiWeapons(AbstractVehicle vehicle, WeaponUnit weaponUnit, int index,
                                   List<AbstractVehicleWeapon<?>> subWeapons, String serializeId) {
        super(vehicle, weaponUnit, index, subWeapons, serializeId);
    }

    @Override
    public void cycleSubWeapon(boolean next) {
        super.cycleSubWeapon(next);
        for (AbstractVehicleWeapon<?> sub : getSubWeapons()) {
            resetLeaves(sub);
        }
    }

    private static void resetLeaves(AbstractVehicleWeapon<?> weapon) {
        if (weapon instanceof VehicleMultiWeapons multi) {
            for (AbstractVehicleWeapon<?> sub : multi.getSubWeapons()) {
                resetLeaves(sub);
            }
        } else {
            weapon.setRemainAmmo(0);
            weapon.startReload();
        }
    }
}
