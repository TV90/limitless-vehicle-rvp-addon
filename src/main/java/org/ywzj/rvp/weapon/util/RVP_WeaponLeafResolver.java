package org.ywzj.rvp.weapon.util;

import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.List;
import java.util.Optional;

/**
 * 当前选中「叶子武器」解析：递归解开嵌套 {@link VehicleMultiWeapons}。
 *
 * <p>动机（2026-10-22 3OF82 无法装订空爆引信）：RVP 分组弹种槽
 * （{@code modding_only_multi} + {@code merge_into_previous_slot}，见
 * {@code RVP_GroupedWeaponSlotAssembler}）会把 AP/HE 两槽装配成嵌套结构——武器站的
 * {@code getCurrentWeapon()} 返回的是外层 {@code VehicleMultiWeapons}（基类本体类，
 * 不是 {@code RVP_WeaponBase}），直接 {@code instanceof RVP_WeaponBase} 必然落空。
 * 参照本体 {@code WeaponUnit.currentWeaponHitPosition()} 的解包写法，但需递归：
 * T90M/M1A2 等为 外层(AP组,HE组) → 内层组 → 叶子武器 两层嵌套。</p>
 *
 * <p>索引口径：叶子武器的 {@code getIndex()} 是它在所属内层 multi 中的序号
 * （如 T90M HE 组 3OF26=0 / 3BK18M=1 / 3OF82=2）。{@code RVP_AirburstRangeStore}
 * 按 (载具, 武器站, 叶子索引) 键控，客户端装订、服务端校验、弹体绑定三处必须
 * 都用同一口径，否则互相找不到。已知边界：不同组的同序号叶子共享一个键
 * （如 AP 组序号 1 与 HE 组序号 1）；仅当两组都存在可编程空爆武器且交替装订时
 * 才会串味，当前配置无此场景。</p>
 */
public final class RVP_WeaponLeafResolver {

    private RVP_WeaponLeafResolver() {}

    /**
     * @return 当前实际选中的叶子武器；武器站为空或无选中项时返回 empty。
     */
    public static Optional<AbstractVehicleWeapon<?>> resolveLeaf(WeaponUnit unit) {
        if (unit == null) {
            return Optional.empty();
        }
        Optional<AbstractVehicleWeapon<?>> current = unit.getCurrentWeapon();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(unwrap(current.get()));
    }

    /**
     * @return 当前选中叶子武器的索引；解析失败时回退本体 {@code getCurrentWeapon()} 的索引，
     *         再不行返回 0（保持旧调用方行为）。
     */
    public static int resolveLeafIndex(WeaponUnit unit) {
        Optional<AbstractVehicleWeapon<?>> leaf = resolveLeaf(unit);
        if (leaf.isPresent()) {
            return leaf.get().getIndex();
        }
        return unit != null
                ? unit.getCurrentWeapon().map(AbstractVehicleWeapon::getIndex).orElse(0)
                : 0;
    }

    private static AbstractVehicleWeapon<?> unwrap(AbstractVehicleWeapon<?> weapon) {
        while (weapon instanceof VehicleMultiWeapons multi) {
            List<AbstractVehicleWeapon<?>> subs = multi.getSubWeapons();
            if (subs.isEmpty()) {
                break;
            }
            int index = multi.getSelectedIndex();
            if (index < 0 || index >= subs.size()) {
                break;
            }
            weapon = subs.get(index);
        }
        return weapon;
    }
}
