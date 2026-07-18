package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

public class RVP_GuidanceDataHITL extends RVP_GuidanceData {

    @SerializedName("hitl_enabled")
    private boolean hitlEnabled = false;

    @SerializedName("hitl_max_turn_deg_per_tick")
    private int hitlMaxTurnDegPerTick = 2;

    @SerializedName("signal_source")
    private String signalSource = "RADIO";

    @SerializedName("hitl_max_control_dist")
    private int hitlMaxControlDist = 600;

    @SerializedName("hitl_max_control_tick")
    private int hitlMaxControlTick = 200;

    @SerializedName("hitl_max_look_offset")
    private int hitlMaxLookOffset = 30;

    @SerializedName("hitl_video_modes")
    private List<String> hitlVideoModes = List.of("MONO");

    public boolean isHitlEnabled() {
        return hitlEnabled;
    }

    public int getHitlMaxTurnDegPerTick() {
        return Math.max(hitlMaxTurnDegPerTick, 0);
    }

    public String getSignalSource() {
        return signalSource == null || signalSource.isBlank()
                ? "RADIO"
                : signalSource.trim().toUpperCase(Locale.ROOT);
    }

    public int getHitlMaxControlDist() {
        return Math.max(hitlMaxControlDist, 0);
    }

    public int getHitlMaxControlTick() {
        return Math.max(hitlMaxControlTick, 0);
    }

    public int getHitlMaxLookOffset() {
        return Math.max(hitlMaxLookOffset, 0);
    }

    public List<String> getHitlVideoModes() {
        return hitlVideoModes == null || hitlVideoModes.isEmpty()
                ? List.of("MONO")
                : List.copyOf(hitlVideoModes);
    }
}
