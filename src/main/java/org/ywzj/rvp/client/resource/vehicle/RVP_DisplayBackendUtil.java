package org.ywzj.rvp.client.resource.vehicle;

import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class RVP_DisplayBackendUtil {

    private static final Map<VehicleBedrockModel, RVP_BedrockBackend> MODEL_BACKENDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<VehicleBedrockModel, Set<String>> MODEL_NO_CULL_BONES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_DisplayBackendUtil() {}

    public static void clearCache() {
        MODEL_BACKENDS.clear();
        MODEL_NO_CULL_BONES.clear();
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

    /**
     * 该模型声明为不启用单面剔除（NO_CULL）的骨骼名集合；未配置时返回空集合。
     */
    public static Set<String> getNoCullBones(@Nullable VehicleBedrockModel model) {
        if (model == null) {
            return Set.of();
        }
        Set<String> noCull = MODEL_NO_CULL_BONES.get(model);
        if (noCull != null) {
            return noCull;
        }
        bindKnownDisplays();
        return MODEL_NO_CULL_BONES.getOrDefault(model, Set.of());
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
            MODEL_NO_CULL_BONES.putIfAbsent(display.getModel(), resolveNoCullBones(display));
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

    private static Set<String> resolveNoCullBones(BaseDisplay display) {
        try {
            Method getter = display.getClass().getMethod("getNoCullBones");
            Object result = getter.invoke(display);
            if (result instanceof Iterable<?> iterable) {
                return collectNames(iterable);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        Field field = findField(display.getClass(), "noCullBones");
        if (field != null) {
            try {
                field.setAccessible(true);
                Object result = field.get(display);
                if (result instanceof Iterable<?> iterable) {
                    return collectNames(iterable);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return Set.of();
    }

    private static Set<String> collectNames(Iterable<?> iterable) {
        Set<String> names = new HashSet<>();
        for (Object item : iterable) {
            if (item != null) {
                names.add(String.valueOf(item));
            }
        }
        return names.isEmpty() ? Set.of() : Collections.unmodifiableSet(names);
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
