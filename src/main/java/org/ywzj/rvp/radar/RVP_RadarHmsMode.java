package org.ywzj.rvp.radar;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public enum RVP_RadarHmsMode {
    OFF,
    FULL,
    ONLY_ACM;

    public boolean isEnabled() {
        return this != OFF;
    }

    public boolean isOnlyAcm() {
        return this == ONLY_ACM;
    }

    public static RVP_RadarHmsMode fromJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return FULL;
        }
        if (!(element instanceof JsonPrimitive primitive)) {
            return FULL;
        }
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean() ? FULL : OFF;
        }
        if (primitive.isString()) {
            return fromString(primitive.getAsString());
        }
        return FULL;
    }

    public static RVP_RadarHmsMode fromString(String raw) {
        if (raw == null) {
            return FULL;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return FULL;
        }
        if ("false".equalsIgnoreCase(normalized) || "off".equalsIgnoreCase(normalized) || "none".equalsIgnoreCase(normalized)) {
            return OFF;
        }
        if ("onlyACM".equalsIgnoreCase(normalized) || "only_acm".equalsIgnoreCase(normalized) || "acm".equalsIgnoreCase(normalized)) {
            return ONLY_ACM;
        }
        return FULL;
    }
}
