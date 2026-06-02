package org.ywzj.rvp.all;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RvpHomingModes;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.weapon.VehicleGPSBomb;
import org.ywzj.rvp.weapon.VehicleAntiRadiationMissile;
import org.ywzj.rvp.weapon.VehicleTVMissile;
import org.ywzj.rvp.weapon.data.VehicleGPSBombWeaponData;
import org.ywzj.rvp.weapon.data.VehicleAntiRadiationMissileWeaponData;
import org.ywzj.rvp.weapon.data.VehicleTVMissileWeaponData;
import org.ywzj.vehicle.all.ModRegistries;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponType;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;

public class RvpVehicleWeaponTypes {

    public static final DeferredRegister<VehicleWeaponType<?, ?>> WEAPON_TYPES =
            DeferredRegister.create(ModRegistries.VEHICLE_WEAPON_TYPE, YwzjRvp.MOD_ID);

    public static final RegistryObject<VehicleWeaponType<VehicleGPSBomb, VehicleGPSBombWeaponData>> GPS_BOMB =
            WEAPON_TYPES.register("gps_bomb",
                    () -> VehicleWeaponType.Builder.<VehicleGPSBomb, VehicleGPSBombWeaponData>of(YwzjRvp.modLocation("gps_bomb"))
                            .setDataSerializer(json -> GsonUtil.GSON.fromJson(json, VehicleGPSBombWeaponData.class))
                            .setFactory(VehicleGPSBomb::new)
                            .build()
            );

    public static final RegistryObject<VehicleWeaponType<VehicleMissile, VehicleMissileWeaponData>> ACTIVE_RADAR_MISSILE =
            WEAPON_TYPES.register("active_radar_missile",
                    () -> VehicleWeaponType.Builder.<VehicleMissile, VehicleMissileWeaponData>of(YwzjRvp.modLocation("active_radar_missile"))
                            .setDataSerializer(json -> deserializeRadarMissile(json, RvpHomingModes.ACTIVE_RADAR))
                            .setFactory(VehicleMissile::new)
                            .build()
            );

    public static final RegistryObject<VehicleWeaponType<VehicleMissile, VehicleMissileWeaponData>> SEMI_ACTIVE_RADAR_MISSILE =
            WEAPON_TYPES.register("semi_active_radar_missile",
                    () -> VehicleWeaponType.Builder.<VehicleMissile, VehicleMissileWeaponData>of(YwzjRvp.modLocation("semi_active_radar_missile"))
                            .setDataSerializer(json -> deserializeRadarMissile(json, RvpHomingModes.SEMI_ACTIVE_RADAR))
                            .setFactory(VehicleMissile::new)
                            .build()
            );

    public static final RegistryObject<VehicleWeaponType<VehicleAntiRadiationMissile, VehicleAntiRadiationMissileWeaponData>> ANTI_RADIATION_MISSILE =
            WEAPON_TYPES.register("anti_radiation_missile",
                    () -> VehicleWeaponType.Builder.<VehicleAntiRadiationMissile, VehicleAntiRadiationMissileWeaponData>of(YwzjRvp.modLocation("anti_radiation_missile"))
                            .setDataSerializer(json -> {
                                if (!json.isJsonObject()) {
                                    return null;
                                }
                                JsonObject obj = json.getAsJsonObject().deepCopy();
                                obj.addProperty("guidance", "PRESET");
                                RvpHomingModes.migrateLegacyHomingFields(obj);
                                if (!obj.has("homing_mode")) {
                                    obj.addProperty("homing_mode", RvpHomingModes.ANTI_RADIATION);
                                }
                                return GsonUtil.GSON.fromJson(obj, VehicleAntiRadiationMissileWeaponData.class);
                            })
                            .setFactory(VehicleAntiRadiationMissile::new)
                            .build()
            );

    public static final RegistryObject<VehicleWeaponType<VehicleTVMissile, VehicleTVMissileWeaponData>> TV_MISSILE =
            WEAPON_TYPES.register("tv_missile",
                    () -> VehicleWeaponType.Builder.<VehicleTVMissile, VehicleTVMissileWeaponData>of(YwzjRvp.modLocation("tv_missile"))
                            .setDataSerializer(json -> {
                                if (!json.isJsonObject()) {
                                    return null;
                                }
                                JsonObject obj = json.getAsJsonObject().deepCopy();
                                obj.addProperty("guidance", "PRESET");
                                RvpHomingModes.migrateLegacyTvMissileFields(obj);
                                return GsonUtil.GSON.fromJson(obj, VehicleTVMissileWeaponData.class);
                            })
                            .setFactory(VehicleTVMissile::new)
                            .build()
            );

    private static VehicleMissileWeaponData deserializeRadarMissile(com.google.gson.JsonElement json, String defaultHomingMode) {
        if (!json.isJsonObject()) {
            return null;
        }
        JsonObject obj = json.getAsJsonObject().deepCopy();
        RvpHomingModes.migrateLegacyHomingFields(obj);
        if (!obj.has("homing_mode")) {
            obj.addProperty("homing_mode", defaultHomingMode);
        }
        return GsonUtil.GSON.fromJson(obj, VehicleMissileWeaponData.class);
    }

    public static void register(IEventBus bus) {
        WEAPON_TYPES.register(bus);
    }
}
