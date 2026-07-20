package org.ywzj.rvp.client.resource.vehicle;

public enum RVP_BedrockBackend {
    VEHICLE,
    RVP;

    public static RVP_BedrockBackend fromString(String value) {
        if (value == null) {
            return VEHICLE;
        }
        return "rvp".equalsIgnoreCase(value) ? RVP : VEHICLE;
    }
}
