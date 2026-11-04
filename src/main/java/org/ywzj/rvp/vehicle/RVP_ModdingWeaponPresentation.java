package org.ywzj.rvp.vehicle;

import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import java.util.List;

/** 改装专用弹种组的展示解析：配置所属槽位与实际发射部件可以属于不同武器站。 */
public final class RVP_ModdingWeaponPresentation {
    private RVP_ModdingWeaponPresentation() {}

    /** 平铺改装组只显示当前弹种，真实可按F切换的大组仍保留列表。 */
    public static AbstractVehicleWeapon<?> collapse(WeaponUnit context, AbstractVehicleWeapon<?> weapon) {
        if (!(weapon instanceof VehicleMultiWeapons multi)) return weapon;
        for (AbstractVehicleWeapon<?> child : multi.getSubWeapons()) {
            if (child instanceof VehicleMultiWeapons) return weapon;
        }
        // 先查HUD实际遍历的宿主站，不能把multi.getWeaponUnit()发射子站当成配置归属。
        WeaponUnit host = context;
        int index = indexOf(host, multi);
        WeaponUnit mount = multi.getWeaponUnit();
        if (index < 0 && mount != null) {
            host = mount.getRootParentWeaponUnit();
            index = indexOf(host, multi);
            if (index < 0) { host = mount; index = indexOf(host, multi); }
        }
        if (host == null || index < 0) return weapon;
        // 调用已有配置管理器，使用找到的宿主部件及槽位查询改装标记。
        var config = RVP_VehicleExtendedConfigManager.INSTANCE;
        if (config.isGroupedSlotCarrier(host.getVehicle(),host.getId(),index)
                || !config.shouldBlockRuntimeMultiCycle(host,index)) return weapon;
        AbstractVehicleWeapon<?> selected = multi.getSelectedWeapon();
        return selected == null ? weapon : selected;
    }

    /** 按对象身份查真正存放该组的槽；不使用发射子站上同号但无关的槽位。 */
    private static int indexOf(WeaponUnit unit, AbstractVehicleWeapon<?> target) {
        if (unit == null) return -1;
        int index = indexOf(unit.weapons,target);
        if (index < 0) index = indexOf(unit.secondaryWeapons,target);
        if (index < 0) index = indexOf(unit.independentWeapons,target);
        return index;
    }

    private static int indexOf(List<AbstractVehicleWeapon<?>> weapons, AbstractVehicleWeapon<?> target) {
        for (int i=0;i<weapons.size();i++) if (weapons.get(i)==target) return i;
        return -1;
    }
}
