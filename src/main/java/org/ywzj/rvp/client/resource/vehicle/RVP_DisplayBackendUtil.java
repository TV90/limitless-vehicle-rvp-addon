package org.ywzj.rvp.client.resource.vehicle;

import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class RVP_DisplayBackendUtil {

    private static final Map<VehicleBedrockModel, RVP_BedrockBackend> MODEL_BACKENDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_DisplayBackendUtil() {}

    public static void clearCache() {
        MODEL_BACKENDS.clear();
    }

    public static boolean isRvpBackend(@Nullable VehicleBedrockModel model) {
        return getBackend(model) == RVP_BedrockBackend.RVP;
    }

    public static RVP_BedrockBackend getBackend(@Nullable VehicleBedrockModel model) {
        if (model == null) {
            return RVP_BedrockBackend.VEHICLE;
        }
        RVP_BedrockBackend backend = MODEL_BACKENDS.get(model);
        if (backend != null) {
            return backend;
        }
        bindKnownDisplays();
        return MODEL_BACKENDS.getOrDefault(model, RVP_BedrockBackend.VEHICLE);
    }

    private static void bindKnownDisplays() {
        Map<?, BaseDisplay> vehicleDisplays = ClientAssetsManager.INSTANCE.getVehicleDisplays();
        if (vehicleDisplays != null) {
            bindDisplays(vehicleDisplays.values());
        }
        bindDisplays(ClientAssetsManager.INSTANCE.getDecorationDisplays());
    }

    private static void bindDisplays(Iterable<? extends BaseDisplay> displays) {
        if (displays == null) {
            return;
        }
        for (BaseDisplay display : displays) {
            if (display == null || display.getModel() == null) {
                continue;
            }
            MODEL_BACKENDS.putIfAbsent(display.getModel(), resolveBackend(display));
        }
    }

    private static RVP_BedrockBackend resolveBackend(BaseDisplay display) {
        try {
            Method getter = display.getClass().getMethod("getBedrockBackend");
            Object result = getter.invoke(display);
            if (result instanceof RVP_BedrockBackend backend) {
                return backend;
            }
            if (result instanceof String text) {
                return RVP_BedrockBackend.fromString(text);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        Field field = findField(display.getClass(), "bedrockBackend");
        if (field != null) {
            try {
                field.setAccessible(true);
                Object result = field.get(display);
                if (result instanceof RVP_BedrockBackend backend) {
                    return backend;
                }
                if (result instanceof String text) {
                    return RVP_BedrockBackend.fromString(text);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return RVP_BedrockBackend.VEHICLE;
    }

    @Nullable
    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
