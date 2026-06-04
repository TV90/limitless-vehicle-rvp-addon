package org.ywzj.rvp.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;

import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds JSON-shaped text from in-memory weapon POJOs on the player's current weapon instance.
 * Uses declared fields + {@link SerializedName} (same keys as pack JSON), not Gson reflective
 * serialization — avoids {@link Ingredient} / {@link Optional} crashes on Java 17+.
 */
public final class RVP_WeaponDataDumper {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_DEPTH = 14;

    private static final Set<Class<?>> BLOCKED_TYPES = Set.of(
            Ingredient.class,
            ItemStack.class,
            Optional.class
    );

    private static final String ALLOWED_PREFIX = "org.ywzj.";

    private RVP_WeaponDataDumper() {}

    public static String toPrettyJson(@Nullable BaseVehicleWeaponData data) {
        if (data == null) {
            return "{}";
        }
        IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        JsonObject root = dumpObject(data, visiting, 0);
        if (data instanceof RVP_WeaponData rvp && rvp.getWeaponKind() != null) {
            root.addProperty("type", rvp.getWeaponKind().name().toLowerCase(Locale.ROOT));
        }
        if (data.getWeaponId() != null) {
            root.addProperty("weapon_id", data.getWeaponId().toString());
        }
        return PRETTY.toJson(root);
    }

    private static JsonObject dumpObject(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        JsonObject out = new JsonObject();
        if (depth > MAX_DEPTH || value == null) {
            return out;
        }
        if (visiting.containsKey(value)) {
            out.addProperty("_ref", value.getClass().getSimpleName());
            return out;
        }
        visiting.put(value, Boolean.TRUE);

        Class<?> clazz = value.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (shouldSkipField(field)) {
                    continue;
                }
                SerializedName name = field.getAnnotation(SerializedName.class);
                if (name == null) {
                    continue;
                }
                String key = name.value();
                if (key.isEmpty()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    JsonElement element = toJsonElement(fieldValue, visiting, depth + 1);
                    if (element != null && !element.isJsonNull()) {
                        out.add(key, element);
                    }
                } catch (IllegalAccessException ignored) {
                    // skip unreadable field
                }
            }
            clazz = clazz.getSuperclass();
        }

        visiting.remove(value);
        return out;
    }

    private static boolean shouldSkipField(Field field) {
        int mod = field.getModifiers();
        if (Modifier.isStatic(mod) || field.isSynthetic()) {
            return true;
        }
        if (Modifier.isTransient(mod)) {
            return true;
        }
        Class<?> type = field.getType();
        return BLOCKED_TYPES.contains(type);
    }

    @Nullable
    private static JsonElement toJsonElement(@Nullable Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (depth > MAX_DEPTH) {
            return new JsonPrimitive("…");
        }
        if (value instanceof JsonElement json) {
            return json.deepCopy();
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof Character c) {
            return new JsonPrimitive(c);
        }
        if (value instanceof String s) {
            if (s.isEmpty()) {
                return JsonNull.INSTANCE;
            }
            return new JsonPrimitive(s);
        }
        if (value instanceof Enum<?> e) {
            return new JsonPrimitive(e.name());
        }
        if (value instanceof ResourceLocation rl) {
            return new JsonPrimitive(rl.toString());
        }
        if (value instanceof RVP_EnumGuidanceType type) {
            return new JsonPrimitive(type.name());
        }
        if (value.getClass().isArray()) {
            return dumpArray(value, visiting, depth);
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object item : collection) {
                JsonElement itemJson = toJsonElement(item, visiting, depth + 1);
                if (itemJson != null) {
                    array.add(itemJson);
                }
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                JsonElement entryJson = toJsonElement(entry.getValue(), visiting, depth + 1);
                if (entryJson != null) {
                    obj.add(key, entryJson);
                }
            }
            return obj;
        }
        if (!isAllowedNestedType(value.getClass())) {
            return new JsonPrimitive(value.toString());
        }
        return dumpObject(value, visiting, depth);
    }

    private static JsonArray dumpArray(Object array, IdentityHashMap<Object, Boolean> visiting, int depth) {
        JsonArray out = new JsonArray();
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            Object item = Array.get(array, i);
            if (item != null && item.getClass().isArray() && item.getClass().getComponentType() == float.class) {
                JsonArray pair = new JsonArray();
                for (float f : (float[]) item) {
                    pair.add(f);
                }
                out.add(pair);
            } else {
                JsonElement element = toJsonElement(item, visiting, depth + 1);
                if (element != null) {
                    out.add(element);
                }
            }
        }
        return out;
    }

    private static boolean isAllowedNestedType(Class<?> clazz) {
        if (clazz.isPrimitive() || clazz.isEnum()) {
            return false;
        }
        String name = clazz.getName();
        return name.startsWith(ALLOWED_PREFIX);
    }
}
