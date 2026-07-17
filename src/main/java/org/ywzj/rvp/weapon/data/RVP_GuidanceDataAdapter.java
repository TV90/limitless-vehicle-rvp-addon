package org.ywzj.rvp.weapon.data;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import java.lang.reflect.Type;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Field adapter for the new single-main/single-terminal guidance schema. */
public final class RVP_GuidanceDataAdapter
        implements JsonDeserializer<RVP_GuidanceData>, JsonSerializer<RVP_GuidanceData> {

    private static final Set<String> GPS_FIELDS = Set.of("gps_spread_radius");
    private static final Set<String> HITL_FIELDS = Set.of(
            "hitl_max_turn_deg_per_tick",
            "signal_source",
            "hitl_max_control_dist",
            "hitl_max_control_tick",
            "hitl_max_look_offset",
            "hitl_video_modes"
    );
    private static final EnumSet<RVP_EnumGuidanceType> HITL_TYPES = EnumSet.of(
            RVP_EnumGuidanceType.TV,
            RVP_EnumGuidanceType.HITL_TV,
            RVP_EnumGuidanceType.HITL_CLOS_TV
    );
    private static final EnumSet<RVP_EnumGuidanceType> TERMINAL_TYPES = EnumSet.of(
            RVP_EnumGuidanceType.NONE,
            RVP_EnumGuidanceType.ATV,
            RVP_EnumGuidanceType.AIR,
            RVP_EnumGuidanceType.ARH,
            RVP_EnumGuidanceType.ARM
    );

    @Override
    public RVP_GuidanceData deserialize(
            JsonElement json,
            Type typeOfT,
            JsonDeserializationContext context
    ) throws JsonParseException {
        if (json == null || json.isJsonNull()) {
            return new RVP_GuidanceData();
        }
        if (!json.isJsonObject()) {
            throw new JsonParseException("guidance_data must be a JSON object");
        }

        JsonObject object = json.getAsJsonObject().deepCopy();
        if (!object.has("guidance_type")) {
            rejectSubtypeFieldsWithoutType(object);
            return context.deserialize(object, RVP_GuidanceData.class);
        }

        RVP_EnumGuidanceType type = parseGuidanceType(object.get("guidance_type"), "guidance_type");
        if (type == RVP_EnumGuidanceType.IOG) {
            throw new JsonParseException("IOG is not a public guidance_type in the new schema");
        }
        object.addProperty("guidance_type", type.name());
        normalizeTerminalType(object);
        validateSubtypeFields(object, type);

        Class<? extends RVP_GuidanceData> targetClass = targetClass(type);
        return context.deserialize(object, targetClass);
    }

    @Override
    public JsonElement serialize(
            RVP_GuidanceData source,
            Type typeOfSrc,
            JsonSerializationContext context
    ) {
        if (source == null) {
            return null;
        }
        RVP_EnumGuidanceType type = source.getGuidanceType();
        Class<? extends RVP_GuidanceData> expected = targetClass(type);
        if (expected != RVP_GuidanceData.class && !expected.isInstance(source)) {
            throw new JsonParseException(type + " guidance requires " + expected.getSimpleName());
        }
        if (expected == RVP_GuidanceData.class
                && (source instanceof RVP_GuidanceDataGPS || source instanceof RVP_GuidanceDataHITL)) {
            throw new JsonParseException(source.getClass().getSimpleName() + " does not match " + type);
        }
        return context.serialize(source, source.getClass());
    }

    private static void rejectSubtypeFieldsWithoutType(JsonObject object) {
        if (containsAny(object, GPS_FIELDS) || containsAny(object, HITL_FIELDS)) {
            throw new JsonParseException("Guidance subtype fields require guidance_type");
        }
    }

    private static void validateSubtypeFields(JsonObject object, RVP_EnumGuidanceType type) {
        if (type != RVP_EnumGuidanceType.GPS && containsAny(object, GPS_FIELDS)) {
            throw new JsonParseException("gps_spread_radius requires guidance_type GPS");
        }
        if (!HITL_TYPES.contains(type) && containsAny(object, HITL_FIELDS)) {
            throw new JsonParseException("HITL fields require guidance_type TV, HITL_TV, or HITL_CLOS_TV");
        }
    }

    private static boolean containsAny(JsonObject object, Set<String> fields) {
        for (String field : fields) {
            if (object.has(field)) {
                return true;
            }
        }
        return false;
    }

    private static Class<? extends RVP_GuidanceData> targetClass(RVP_EnumGuidanceType type) {
        if (type == RVP_EnumGuidanceType.GPS) {
            return RVP_GuidanceDataGPS.class;
        }
        if (HITL_TYPES.contains(type)) {
            return RVP_GuidanceDataHITL.class;
        }
        return RVP_GuidanceData.class;
    }

    private static void normalizeTerminalType(JsonObject object) {
        if (!object.has("terminal_guidance") || !object.get("terminal_guidance").isJsonObject()) {
            return;
        }
        JsonObject terminal = object.getAsJsonObject("terminal_guidance");
        if (!terminal.has("guidance_type")) {
            return;
        }
        RVP_EnumGuidanceType terminalType = parseGuidanceType(
                terminal.get("guidance_type"),
                "terminal_guidance.guidance_type"
        );
        if (!TERMINAL_TYPES.contains(terminalType)) {
            throw new JsonParseException("Unsupported terminal guidance_type: " + terminalType);
        }
        terminal.addProperty("guidance_type", terminalType.name());
    }

    private static RVP_EnumGuidanceType parseGuidanceType(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(field + " must be a string");
        }
        String raw = element.getAsString().trim();
        try {
            return RVP_EnumGuidanceType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Unknown " + field + ": " + raw, exception);
        }
    }
}
