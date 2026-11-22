package org.ywzj.rvp.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.custom.weapon.data.VehicleGrenadeWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleGrenade;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.*;

class RVP_WorkshopStationTest {
    @BeforeAll
    static void bootstrap() { initialize(); }

    @Test
    void ztz100FixedWeaponsAreVisibleWithoutApsAndDoNotSubmitChanges() throws Exception {
        JsonObject raw;
        try (var reader = Files.newBufferedReader(Path.of("limitless_vehicle/rvp/data/rvp/vehicles/ztz100.json"))) {
            raw = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray parts = raw.getAsJsonArray("parts");
        TrackedVehicle vehicle = environment(TrackedVehicle.class);
        write(AbstractVehicle.class, vehicle, "vehicleId", ResourceLocation.tryParse("rvp:ztz100"));
        WeaponUnit turret = unit("turret", 1);
        WeaponUnit coax = unit("turret_machine_gun", 2);
        WeaponUnit commander = unit("commander_machine_gun", 3);
        WeaponUnit laser = unit("commander_laser", 4);
        WeaponUnit apsLeft = unit("aps_l", 5);
        WeaponUnit apsRight = unit("aps_r", 6);
        write(AbstractVehicle.class, vehicle, "partUnits", List.of(turret, coax, commander, laser, apsLeft, apsRight));
        write(AbstractVehicle.class, vehicle, "partUnitMap", Map.of(
                turret.getId(), turret, coax.getId(), coax, commander.getId(), commander, laser.getId(), laser,
                apsLeft.getId(), apsLeft, apsRight.getId(), apsRight));
        write(WeaponUnit.class, coax, "parentWeaponUnit", turret);
        write(WeaponUnit.class, laser, "parentWeaponUnit", commander);

        var mainAmmo = weapons(parts, turret).get(0).getAsJsonArray();
        var group = new VehicleMultiWeapons(vehicle, turret, 0, List.of(
                weapon(vehicle, turret, mainAmmo.get(0).getAsString()),
                weapon(vehicle, turret, mainAmmo.get(1).getAsString())), "main");
        group.setSelectedIndex(1);
        install(turret, group);
        String coaxId = weapons(parts, coax).get(0).getAsString();
        install(coax, weapon(vehicle, coax, coaxId));
        // 炮塔副槽和实体子站可各有同型武器实例，左栏应显示一次。
        var turretCoax = weapons(parts, turret).get(1).getAsJsonObject();
        assertEquals(coax.getId(), turretCoax.get("part_unit_id").getAsString());
        turret.getIndexedWeapons().add(weapon(vehicle, coax, turretCoax.get("id").getAsString()));

        String commanderId = weapons(parts, commander).get(0).getAsString();
        String laserId = weapons(parts, laser).get(0).getAsString();
        install(commander, weapon(vehicle, commander, commanderId));
        install(laser, weapon(vehicle, laser, laserId));
        install(commander, new VehicleWeaponAgent(vehicle, laser, 1));
        for (var launcher : List.of(apsLeft, apsRight)) {
            String weaponId = weapons(parts, launcher).get(0).getAsString();
            var data = GsonUtil.GSON.fromJson("{\"grenade\":\"aps\"}", VehicleGrenadeWeaponData.class);
            data.setWeaponId(ResourceLocation.tryParse(weaponId));
            launcher.getIndexedWeapons().add(new VehicleGrenade(vehicle, launcher, 0, data, weaponId));
        }

        var stations = RVP_WorkshopStation.collect(vehicle);
        var visibleIds = List.of(mainAmmo.get(1).getAsString(), coaxId, commanderId, laserId);
        assertEquals(visibleIds, weaponIds(stations));
        assertTrue(stations.stream().allMatch(station -> station.size() == 1 && station.equipped() == 0));

        // 自定义发射器改用普通武器类型时，仍按载具的 APS 模块配置隐藏。
        apsLeft.getIndexedWeapons().set(0, weapon(vehicle, apsLeft, "workshop_test:interceptor"));
        var config = RVP_VehicleHitboxFactorManager.INSTANCE;
        var configFields = List.of("configs", "boneModuleViews", "hidePassengerVehicles");
        var previous = configFields.stream().map(field -> read(config.getClass(), config, field)).toList();
        try {
            config.applyFromJsonMap(Map.of(vehicle.getVehicleId(), raw));
            assertEquals(visibleIds, weaponIds(RVP_WorkshopStation.collect(vehicle)));
        } finally {
            for (int index = 0; index < configFields.size(); index++)
                write(config.getClass(), config, configFields.get(index), previous.get(index));
        }

        // 未初始化网络或客户端的情况下点击单项也能完成，说明没有提交改装。
        var screen = environment(RVP_AuiVariantScreen.class);
        write(RVP_AuiVariantScreen.class, screen, "stations", stations);
        var equip = RVP_AuiVariantScreen.class.getDeclaredMethod("equipWeapon", int.class);
        equip.setAccessible(true);
        for (int index = 0; index < stations.size(); index++) {
            write(RVP_AuiVariantScreen.class, screen, "stationIndex", index);
            assertDoesNotThrow(() -> equip.invoke(screen, 0));
        }
    }

    private static List<String> weaponIds(List<RVP_WorkshopStation> stations) {
        return stations.stream().map(station -> station.weapon(0).getData().getWeaponId().toString()).toList();
    }

    private static JsonArray weapons(JsonArray parts, WeaponUnit unit) {
        for (var part : parts) {
            var raw = part.getAsJsonObject();
            if (unit.getId().equals(raw.get("id").getAsString())) return raw.getAsJsonArray("weapons");
        }
        throw new AssertionError(unit.getId());
    }

    private static WeaponUnit unit(String id, int index) {
        var unit = environment(WeaponUnit.class);
        write(PartUnit.class, unit, "id", id);
        write(PartUnit.class, unit, "index", index);
        write(PartUnit.class, unit, "name", Component.literal(id));
        write(WeaponUnit.class, unit, "weapons", new ArrayList<>());
        write(WeaponUnit.class, unit, "indexedWeapons", new ArrayList<>());
        return unit;
    }

    private static AbstractVehicleWeapon<?> weapon(AbstractVehicle vehicle, WeaponUnit unit, String id) {
        var data = new RVP_WeaponData();
        data.setWeaponId(ResourceLocation.tryParse(id));
        return new RVP_ProjectileWeapon(vehicle, unit, 0, data, id, null);
    }

    private static void install(WeaponUnit unit, AbstractVehicleWeapon<?> weapon) {
        unit.weapons.add(weapon);
        unit.getIndexedWeapons().add(weapon);
    }
}
