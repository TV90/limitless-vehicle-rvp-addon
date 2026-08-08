package org.ywzj.rvp.vehicle;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.WeaponInfo;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RVP_GroupedWeaponSlotAssembler {

    private RVP_GroupedWeaponSlotAssembler() {}

    public static boolean shouldHandle(WeaponUnit weaponUnit) {
        return weaponUnit != null && RVP_VehicleExtendedConfigManager.INSTANCE.hasGroupedWeaponSlots(
                weaponUnit.getVehicle(), weaponUnit.getId());
    }

    public static void assemble(WeaponUnit weaponUnit, Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        weaponUnit.weapons.clear();
        weaponUnit.secondaryWeapons.clear();
        weaponUnit.independentWeapons.clear();
        weaponUnit.indexedWeapons.clear();
        weaponUnit.weaponBayUnits.clear();

        linkSubParts(weaponUnit, partUnitsView);

        List<SlotBlueprint> slots = buildBlueprints(weaponUnit);
        int topLevelIndex = 0;
        for (SlotBlueprint slot : slots) {
            BuiltWeapon built = buildSlot(weaponUnit, vehicle, partUnitsView, slot, topLevelIndex);
            if (built == null || built.weapon() == null) {
                continue;
            }
            built.weapon().defineSyncData(weaponUnit.getSyncData());
            if (built.independent()) {
                weaponUnit.independentWeapons.add(built.weapon());
            } else if (built.secondary()) {
                weaponUnit.secondaryWeapons.add(built.weapon());
            } else {
                weaponUnit.weapons.add(built.weapon());
            }
            weaponUnit.indexedWeapons.add(built.weapon());
            if (built.weaponBayUnit() != null) {
                weaponUnit.weaponBayUnits.put(built.weapon(), built.weaponBayUnit());
            }
            topLevelIndex++;
        }
    }

    private static void linkSubParts(WeaponUnit weaponUnit, Map<String, PartUnit<?>> partUnitsView) {
        WeaponUnitData data = weaponUnit.getData();
        if (data == null) {
            return;
        }
        for (String subPartUnitId : data.getSubPartUnitIds()) {
            PartUnit<?> subPartUnit = partUnitsView.get(subPartUnitId);
            if (subPartUnit == null) {
                continue;
            }
            if (!weaponUnit.getSubPartUnits().contains(subPartUnit)) {
                weaponUnit.addSubPartUnit(subPartUnit);
            }
            subPartUnit.setParentPartUnit(weaponUnit);
            if (subPartUnit instanceof RadarUnit radarUnit && !weaponUnit.getRadarUnits().contains(radarUnit)) {
                weaponUnit.getRadarUnits().add(radarUnit);
            }
        }
    }

    private static List<SlotBlueprint> buildBlueprints(WeaponUnit weaponUnit) {
        List<SlotBlueprint> slots = new ArrayList<>();
        List<WeaponInfo> infos = weaponUnit.getData().getWeapons();
        if (infos == null || infos.isEmpty()) {
            return slots;
        }
        for (int i = 0; i < infos.size(); i++) {
            WeaponInfo current = infos.get(i);
            if (current == null) {
                continue;
            }
            boolean mergeCurrent = RVP_VehicleExtendedConfigManager.INSTANCE.isMergeIntoPreviousSlot(
                    weaponUnit.getVehicle(), weaponUnit.getId(), i);
            if (mergeCurrent) {
                continue;
            }

            if (i + 1 < infos.size()
                    && RVP_VehicleExtendedConfigManager.INSTANCE.isMergeIntoPreviousSlot(
                    weaponUnit.getVehicle(), weaponUnit.getId(), i + 1)) {
                WeaponInfo next = infos.get(i + 1);
                if (next != null
                        && current.secondary == next.secondary
                        && !isIndependentWeaponEntry(current)
                        && !isIndependentWeaponEntry(next)) {
                    slots.add(SlotBlueprint.grouped(current, next));
                    i++;
                    continue;
                }
            }

            slots.add(SlotBlueprint.single(current));
        }
        return slots;
    }

    @Nullable
    private static BuiltWeapon buildSlot(WeaponUnit root, AbstractVehicle vehicle,
                                         Map<String, PartUnit<?>> partUnitsView,
                                         SlotBlueprint slot, int topLevelIndex) {
        if (!slot.grouped()) {
            return buildWeaponEntry(root, vehicle, partUnitsView, slot.primary(), topLevelIndex);
        }

        BuiltWeapon leftBuilt = buildWeaponEntry(root, vehicle, partUnitsView, slot.primary(), 0);
        BuiltWeapon rightBuilt = buildWeaponEntry(root, vehicle, partUnitsView, slot.linked(), 1);
        if (leftBuilt == null || leftBuilt.weapon() == null || rightBuilt == null || rightBuilt.weapon() == null) {
            return leftBuilt != null ? leftBuilt : rightBuilt;
        }

        String outerSaveId = normalizedSaveId(slot.primary().saveId, "weapon_slot_" + topLevelIndex) + "_slot";
        VehicleMultiWeapons outer = new RVP_VehicleMultiWeapons(
                vehicle,
                root,
                topLevelIndex,
                List.of(leftBuilt.weapon(), rightBuilt.weapon()),
                outerSaveId
        );

        WeaponBayUnit bayUnit = leftBuilt.weaponBayUnit() != null ? leftBuilt.weaponBayUnit() : rightBuilt.weaponBayUnit();
        return new BuiltWeapon(outer, bayUnit, slot.secondary(), false);
    }

    @Nullable
    private static BuiltWeapon buildWeaponEntry(WeaponUnit root, AbstractVehicle vehicle,
                                                Map<String, PartUnit<?>> partUnitsView,
                                                WeaponInfo info, int index) {
        if (info == null) {
            return null;
        }

        WeaponUnit mountUnit = resolveMountUnit(root, partUnitsView, info);
        WeaponBayUnit weaponBayUnit = resolveWeaponBayUnit(partUnitsView, info);
        boolean independent = isIndependentWeaponEntry(info);

        if (info.ids != null && !info.ids.isEmpty()) {
            List<AbstractVehicleWeapon<?>> subWeapons = new ArrayList<>();
            for (ResourceLocation id : info.ids) {
                VehicleWeaponIndex<?, ?> subIndex = CommonAssetsManager.vehicleWeaponManager().getIndex(id).orElse(null);
                if (subIndex == null) {
                    continue;
                }
                String subSaveId = normalizedSaveId(info.saveId, "weapon_" + index) + "_" + id.toString().replace(':', '_');
                AbstractVehicleWeapon<?> sub = subIndex.create(vehicle, mountUnit, subWeapons.size(), subSaveId);
                subWeapons.add(sub);
            }
            if (subWeapons.isEmpty()) {
                return null;
            }
            VehicleMultiWeapons multi = new RVP_VehicleMultiWeapons(
                    vehicle,
                    mountUnit,
                    index,
                    subWeapons,
                    normalizedSaveId(info.saveId, "weapon_" + index)
            );
            return new BuiltWeapon(multi, weaponBayUnit, info.secondary, independent);
        }

        if (info.id == null) {
            if (info.partUnitId != null
                    && partUnitsView.get(info.partUnitId) instanceof WeaponUnit agentWeaponUnit
                    && agentWeaponUnit != root) {
                if (agentWeaponUnit.getParentWeaponUnit() == null) {
                    agentWeaponUnit.setParentWeaponUnit(root);
                }
                root.addSubWeaponUnit(agentWeaponUnit);
                VehicleWeaponAgent weaponAgent = new VehicleWeaponAgent(vehicle, agentWeaponUnit, index);
                return new BuiltWeapon(weaponAgent, weaponBayUnit, info.secondary, independent);
            }
            return null;
        }

        VehicleWeaponIndex<?, ?> weaponIndex = CommonAssetsManager.vehicleWeaponManager().getIndex(info.id).orElse(null);
        if (weaponIndex == null) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = weaponIndex.create(
                vehicle,
                mountUnit,
                index,
                normalizedSaveId(info.saveId, info.id.toString().replace(':', '_'))
        );
        return new BuiltWeapon(weapon, weaponBayUnit, info.secondary, weaponIndex.data().independent);
    }

    private static WeaponUnit resolveMountUnit(WeaponUnit root, Map<String, PartUnit<?>> partUnitsView, WeaponInfo info) {
        if (info.partUnitId != null
                && partUnitsView.get(info.partUnitId) instanceof WeaponUnit subWeaponUnit
                && subWeaponUnit != root) {
            if (subWeaponUnit.getParentWeaponUnit() == null) {
                subWeaponUnit.setParentWeaponUnit(root);
            }
            root.addSubWeaponUnit(subWeaponUnit);
            return subWeaponUnit;
        }
        return root;
    }

    @Nullable
    private static WeaponBayUnit resolveWeaponBayUnit(Map<String, PartUnit<?>> partUnitsView, WeaponInfo info) {
        if (info.weaponBayUnitId != null
                && partUnitsView.get(info.weaponBayUnitId) instanceof WeaponBayUnit weaponBayUnit) {
            return weaponBayUnit;
        }
        return null;
    }

    private static boolean isIndependentWeaponEntry(WeaponInfo info) {
        ResourceLocation firstId = firstWeaponId(info);
        if (firstId == null) {
            return false;
        }
        return CommonAssetsManager.vehicleWeaponManager()
                .getIndex(firstId)
                .map(index -> index.data().independent)
                .orElse(false);
    }

    @Nullable
    private static ResourceLocation firstWeaponId(WeaponInfo info) {
        if (info == null) {
            return null;
        }
        if (info.id != null) {
            return info.id;
        }
        if (info.ids != null && !info.ids.isEmpty()) {
            return info.ids.get(0);
        }
        return null;
    }

    private static String normalizedSaveId(@Nullable String saveId, String fallback) {
        return saveId == null || saveId.isBlank() ? fallback : saveId;
    }

    private record SlotBlueprint(
            WeaponInfo primary,
            WeaponInfo linked,
            boolean grouped,
            boolean secondary
    ) {
        static SlotBlueprint single(WeaponInfo info) {
            return new SlotBlueprint(info, null, false, info.secondary);
        }

        static SlotBlueprint grouped(WeaponInfo primary, WeaponInfo linked) {
            return new SlotBlueprint(primary, linked, true, primary.secondary);
        }
    }

    private record BuiltWeapon(
            AbstractVehicleWeapon<?> weapon,
            WeaponBayUnit weaponBayUnit,
            boolean secondary,
            boolean independent
    ) {}
}
