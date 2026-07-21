package org.ywzj.rvp.all;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.resource.vehicle.RVP_BaseDisplay;
import org.ywzj.rvp.client.resource.vehicle.RVP_BaseDisplayPojo;
import org.ywzj.rvp.client.resource.vehicle.RVP_FixedWingVehicleDisplay;
import org.ywzj.rvp.client.resource.vehicle.RVP_FixedWingVehicleDisplayPojo;
import org.ywzj.rvp.client.resource.vehicle.RVP_RotaryWingVehicleDisplay;
import org.ywzj.rvp.client.resource.vehicle.RVP_SimpleVehicleDisplay;
import org.ywzj.rvp.client.resource.vehicle.RVP_TrackedVehicleDisplay;
import org.ywzj.rvp.client.resource.vehicle.RVP_TrackedVehicleDisplayPojo;
import org.ywzj.rvp.client.resource.vehicle.RVP_WheeledVehicleDisplay;
import org.ywzj.vehicle.all.ModRegistries;
import org.ywzj.vehicle.client.render.animation.context.AnimationContextFactory;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplayType;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

public final class RVP_DisplayTypes {

    public static final DeferredRegister<VehicleDisplayType<?>> VEHICLE_DISPLAY_TYPES =
            DeferredRegister.create(ModRegistries.VEHICLE_DISPLAY_TYPE, RVP_MOD.MOD_ID);

    public static final RegistryObject<VehicleDisplayType<RVP_SimpleVehicleDisplay>> GENERIC_VEHICLE = register(
            "generic",
            json -> new RVP_SimpleVehicleDisplay(GsonUtil.GSON.fromJson(json, RVP_BaseDisplayPojo.class)),
            AnimationContextFactory.vehicle()
    );

    public static final RegistryObject<VehicleDisplayType<RVP_BaseDisplay>> WEAPON = register(
            "weapon",
            json -> new RVP_BaseDisplay(GsonUtil.GSON.fromJson(json, RVP_BaseDisplayPojo.class)),
            null
    );

    public static final RegistryObject<VehicleDisplayType<RVP_WheeledVehicleDisplay>> WHEELED_VEHICLE = register(
            "wheeled_vehicle",
            json -> new RVP_WheeledVehicleDisplay(GsonUtil.GSON.fromJson(json, RVP_BaseDisplayPojo.class)),
            AnimationContextFactory.wheeledVehicle()
    );

    public static final RegistryObject<VehicleDisplayType<RVP_TrackedVehicleDisplay>> TRACKED_VEHICLE = register(
            "tracked_vehicle",
            json -> new RVP_TrackedVehicleDisplay(GsonUtil.GSON.fromJson(json, RVP_TrackedVehicleDisplayPojo.class)),
            AnimationContextFactory.trackedVehicle()
    );

    public static final RegistryObject<VehicleDisplayType<RVP_RotaryWingVehicleDisplay>> ROTARY_WING_VEHICLE = register(
            "rotary_wing_vehicle",
            json -> new RVP_RotaryWingVehicleDisplay(GsonUtil.GSON.fromJson(json, RVP_BaseDisplayPojo.class)),
            AnimationContextFactory.rotaryWingVehicle()
    );

    public static final RegistryObject<VehicleDisplayType<RVP_FixedWingVehicleDisplay>> FIXED_WING_VEHICLE = register(
            "fixed_wing_vehicle",
            json -> new RVP_FixedWingVehicleDisplay(GsonUtil.GSON.fromJson(json, RVP_FixedWingVehicleDisplayPojo.class)),
            AnimationContextFactory.fixedWingVehicle()
    );

    private static <D extends org.ywzj.vehicle.client.resource.vehicle.BaseDisplay> RegistryObject<VehicleDisplayType<D>> register(
            String name,
            VehicleDisplayType.DataSerializer<D> dataSerializer,
            AnimationContextFactory<?, ?> contextFactory
    ) {
        return VEHICLE_DISPLAY_TYPES.register(name,
                () -> VehicleDisplayType.Builder.<D>of(RVP_MOD.modLocation(name))
                        .setDataSerializer(dataSerializer)
                        .setContextFactory(contextFactory)
                        .build()
        );
    }

    public static void register(IEventBus eventBus) {
        VEHICLE_DISPLAY_TYPES.register(eventBus);
    }
}
