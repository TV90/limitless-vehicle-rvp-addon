package org.ywzj.rvp.client.screen;

import net.minecraft.client.resources.language.I18n;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.C2SSelectModdingSubWeapon;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientVehicleSelectPartWeapon;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一个可选择的改装栏。unit 为所属站，group 为配置寻址的多弹种组（本体顶层选择时为空），
 * slot / groupIndex 是原网络包使用的外层槽和组序号，title 是本地化的栏名。
 */
record RVP_WorkshopStation(WeaponUnit unit, VehicleMultiWeapons group, int slot, int groupIndex, String title) {

    /** 从当前实体已装配的武器和挂架配置构建列表，保持服务端的槽位和子弹种顺序。 */
    static List<RVP_WorkshopStation> collect(AbstractVehicle vehicle) {
        var stations = new ArrayList<RVP_WorkshopStation>();
        var configs = RVP_VehicleExtendedConfigManager.INSTANCE;
        Set<String> groupedUnits = new HashSet<>();
        var groups = configs.getModdingGroupEntries(vehicle);
        // 菜单按包内槽位编排；同一站的编号与其网络槽位保持一致。
        groups.sort(Comparator.comparingInt(RVP_VehicleExtendedConfigManager.ModdingGroupEntry::weaponIndex)
                .thenComparingInt(RVP_VehicleExtendedConfigManager.ModdingGroupEntry::groupIndex));
        var counts = new HashMap<String, Integer>();
        for (var entry : groups) counts.merge(entry.partId(), 1, Integer::sum);
        var ordinals = new HashMap<String, Integer>();
        for (var entry : groups) {
            if (!(vehicle.getPartUnit(entry.partId()).orElse(null) instanceof WeaponUnit unit)
                    || entry.weaponIndex() < 0 || entry.weaponIndex() >= unit.weapons.size()) continue;
            VehicleMultiWeapons group;
            if (entry.groupIndex() >= 0) {
                if (!(unit.weapons.get(entry.weaponIndex()) instanceof VehicleMultiWeapons outer)
                        || entry.groupIndex() >= outer.getSubWeapons().size()
                        || !(outer.getSubWeapons().get(entry.groupIndex()) instanceof VehicleMultiWeapons inner)) continue;
                group = inner;
            } else {
                // 通过已有配置解析代理、副武器及普通多弹种槽。
                group = configs.resolveModdingTargetMulti(unit, entry.weaponIndex());
            }
            if (group == null || group.getSubWeapons().isEmpty()) continue;
            stations.add(new RVP_WorkshopStation(unit, group, entry.weaponIndex(), entry.groupIndex(),
                    groupTitle(entry.groupId(), unit, counts.get(entry.partId()),
                            ordinals.merge(entry.partId(), 1, Integer::sum))));
            groupedUnits.add(unit.getId());
        }
        Set<String> pylons = new HashSet<>();
        for (var mount : RVP_CustomMountConfigCache.get(vehicle.getVehicleId())) {
            if (mount.weaponId() != null) pylons.add(mount.partUnitId());
        }
        for (var part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit) || unit.weapons.isEmpty()) continue;
            // 配置挂架也允许浏览单个挂点。本体可改站保留顶层选择，避免把多弹种的承载槽重复列出。
            if (!pylons.contains(unit.getId()) && !(unit.isInteractive() && unit.weapons.size() > 1
                    && !groupedUnits.contains(unit.getId()))) continue;
            stations.add(new RVP_WorkshopStation(unit, null, -1, -1, unitTitle(unit)));
        }
        // 主站、副站和其他席位按载具定义的部件顺序展示，不依赖哈希表遍历顺序。
        stations.sort(Comparator.comparingInt(station -> station.unit().getIndex()));
        return stations;
    }

    private static String groupTitle(String id, WeaponUnit unit, int count, int ordinal) {
        if (id != null && !id.isBlank()) {
            String key = id.toLowerCase(Locale.ROOT);
            if (key.endsWith("_ap")) return I18n.get("gui.ywzj_rvp.variants.ammo.anti_armor");
            if (key.endsWith("_he")) return I18n.get("gui.ywzj_rvp.variants.ammo.anti_infantry");
        }
        // 小标题标明实际武器站，同一站的多个槽以包内顺序区分；大字始终显示当前装备。
        return count > 1 ? I18n.get("gui.ywzj_rvp.workshop.station_slot", unitTitle(unit), ordinal) : unitTitle(unit);
    }

    private static String unitTitle(WeaponUnit unit) {
        String name = unit.getName().getString();
        return name.isBlank() || name.equals(unit.getId()) ? unit.getId().replaceFirst("^variable_", "").replace('_', ' ') : name;
    }

    int size() {
        return group == null ? unit.weapons.size() : group.getSubWeapons().size();
    }

    AbstractVehicleWeapon<?> weapon(int index) {
        // 本体顶层项按原选单展开代理，分组项按子弹种下标寻址。
        return group == null ? unit.proxyWeapon(unit.weapons.get(index)) : group.getSubWeapons().get(index);
    }

    int equipped() {
        return group == null ? unit.getCurrentWeaponIndex() : group.getSelectedIndex();
    }

    void equip(AbstractVehicle vehicle, int index) {
        if (group == null) {
            Channel.CHANNEL.sendToServer(new ClientVehicleSelectPartWeapon(vehicle.getId(), unit.getIndex(), index));
        } else {
            RVP_Network.CHANNEL.sendToServer(new C2SSelectModdingSubWeapon(
                    vehicle.getId(), unit.getId(), slot, index, groupIndex));
        }
    }
}
