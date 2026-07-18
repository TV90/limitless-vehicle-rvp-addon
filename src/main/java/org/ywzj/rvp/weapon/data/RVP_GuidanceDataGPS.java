package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

public class RVP_GuidanceDataGPS extends RVP_GuidanceDataHITL {

    @SerializedName("gps_spread_radius")
    private float gpsSpreadRadius = 0f;

    public float getGpsSpreadRadius() {
        return Math.max(gpsSpreadRadius, 0f);
    }
}
