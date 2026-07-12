package org.ywzj.rvp.client.render;

import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

public final class RVP_CockpitPassengerRenderContext {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private RVP_CockpitPassengerRenderContext() {}

    public static void begin(AbstractVehicle vehicle, float partialTick) {
        if (vehicle == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new Context(vehicle, partialTick));
    }

    public static void end() {
        CURRENT.remove();
    }

    @Nullable
    public static Context current() {
        return CURRENT.get();
    }

    public record Context(AbstractVehicle vehicle, float partialTick) {}
}
