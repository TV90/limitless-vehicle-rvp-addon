package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/** 固定 GPS 目标虚拟中段配置；阶段 A 仅在服务器内存中生效。 */
public final class RVP_VirtualMidcourseData {
    /** 是否启用虚拟中段；默认 false，只有显式启用的武器生效。 */
    @SerializedName("enabled") private boolean enabled = false;
    /** 允许进入虚拟态的最小飞行时间，单位 tick，默认 100。 */
    @SerializedName("entry_min_flight_tick") private int entryMinFlightTick = 100;
    /** 允许进入虚拟态的最小离发射点距离，单位格，默认 800。 */
    @SerializedName("entry_min_distance_from_launch") private double entryMinDistanceFromLaunch = 800.0;
    /** 允许进入虚拟态时距固定目标的最小距离，单位格，默认 1600。 */
    @SerializedName("entry_min_target_distance") private double entryMinTargetDistance = 1600.0;
    /** 开始恢复实体的目标距离下限，单位格，默认 768。 */
    @SerializedName("restore_target_distance") private double restoreTargetDistance = 768.0;
    /** 按当前速度提前恢复的时间，单位 tick，默认 60。 */
    @SerializedName("restore_lead_tick") private int restoreLeadTick = 60;
    /** 恢复点区块票据的方形半径，单位 chunk，默认 1（3x3）。 */
    @SerializedName("restore_ticket_radius") private int restoreTicketRadius = 1;
    /** 等待恢复区块可承载实体的超时，单位 tick，默认 200；超时丢弃记录。 */
    @SerializedName("restore_wait_timeout_tick") private int restoreWaitTimeoutTick = 200;
    /** 虚拟积分间隔，单位 tick，默认 1；阶段 A 固定按 1 使用。 */
    @SerializedName("virtual_update_interval_tick") private int virtualUpdateIntervalTick = 1;
    /** 单枚导弹最大虚拟飞行时间，单位 tick，默认 12000；超过后丢弃。 */
    @SerializedName("max_virtual_flight_tick") private int maxVirtualFlightTick = 12000;
    /** 可选巡航高度，单位世界 Y；默认 null，阶段 A 仍以 guidance_data 的 GPS 巡航参数为准。 */
    @SerializedName("cruise_altitude") private Double cruiseAltitude;
    /** 目标更新方式；默认 FIXED_SNAPSHOT，阶段 A 只接受该值。 */
    @SerializedName("target_update_mode") private String targetUpdateMode = "FIXED_SNAPSHOT";
    /** 恢复超时策略；默认 DISCARD，阶段 A 只接受该值。 */
    @SerializedName("on_restore_timeout") private String onRestoreTimeout = "DISCARD";

    public boolean isEnabled() { return enabled; }
    public int getEntryMinFlightTick() { return Math.max(entryMinFlightTick, 0); }
    public double getEntryMinDistanceFromLaunch() { return Math.max(entryMinDistanceFromLaunch, 0.0); }
    public double getEntryMinTargetDistance() { return Math.max(entryMinTargetDistance, 0.0); }
    public double getRestoreTargetDistance() { return Math.max(restoreTargetDistance, 0.0); }
    public int getRestoreLeadTick() { return Math.max(restoreLeadTick, 0); }
    public int getRestoreTicketRadius() { return Math.max(0, Math.min(restoreTicketRadius, 4)); }
    public int getRestoreWaitTimeoutTick() { return Math.max(restoreWaitTimeoutTick, 1); }
    public int getVirtualUpdateIntervalTick() { return Math.max(virtualUpdateIntervalTick, 1); }
    public int getMaxVirtualFlightTick() { return Math.max(maxVirtualFlightTick, 1); }
    public Double getCruiseAltitude() { return cruiseAltitude; }
    public boolean usesFixedSnapshotTarget() { return "FIXED_SNAPSHOT".equalsIgnoreCase(targetUpdateMode); }
    public boolean discardsOnRestoreTimeout() { return "DISCARD".equalsIgnoreCase(onRestoreTimeout); }
}
