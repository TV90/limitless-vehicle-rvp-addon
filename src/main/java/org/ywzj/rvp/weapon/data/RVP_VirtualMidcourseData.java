package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 固定 GPS 目标虚拟中段配置。
 *
 * <p>阶段 B 由服务器 SavedData 持久化运行状态；本类只定义准入、恢复和安全上限。
 * 转向约束统一读取 {@link RVP_ProjectileData}，避免真实态与虚拟态使用两套机动参数。</p>
 */
public final class RVP_VirtualMidcourseData {
    /** 是否启用虚拟中段；默认 false，只有显式启用的武器生效。 */
    @SerializedName("enabled") private boolean enabled = false;
    /** 允许进入虚拟态的最小飞行时间，单位 tick，默认 0；仅在 {@code enabled=true} 时参与准入。 */
    @SerializedName("entry_min_flight_tick") private int entryMinFlightTick = 0;
    /** 允许进入虚拟态的最小离发射点距离，单位格，默认 800。 */
    @SerializedName("entry_min_distance_from_launch") private double entryMinDistanceFromLaunch = 800.0;
    /** 允许进入虚拟态时距固定目标的最小距离，单位格，默认 1600。 */
    @SerializedName("entry_min_target_distance") private double entryMinTargetDistance = 1600.0;
    /** 开始恢复实体的目标距离下限，单位格，默认 768。 */
    @SerializedName("restore_target_distance") private double restoreTargetDistance = 768.0;
    /** 按当前速度提前恢复的时间，单位 tick，默认 2；仅在虚拟态计算恢复触发距离时生效。 */
    @SerializedName("restore_lead_tick") private int restoreLeadTick = 2;
    /** 恢复点区块票据的方形半径，单位 chunk，默认 1（3x3）。 */
    @SerializedName("restore_ticket_radius") private int restoreTicketRadius = 1;
    /** 等待恢复区块可承载实体的超时，单位 tick，默认 200；超时丢弃记录。 */
    @SerializedName("restore_wait_timeout_tick") private int restoreWaitTimeoutTick = 200;
    /** 虚拟积分间隔，单位 tick，默认 1；阶段 B 固定按 1 使用，暂不启用批量积分。 */
    @SerializedName("virtual_update_interval_tick") private int virtualUpdateIntervalTick = 1;
    /** 单枚导弹最大虚拟飞行时间，单位 tick，默认 12000；超过后丢弃。 */
    @SerializedName("max_virtual_flight_tick") private int maxVirtualFlightTick = 12000;
    /**
     * 可选虚拟巡航高度，单位世界 Y，默认 null；仅在 GPS 巡航阶段生效，配置后闭环跟踪
     * 该绝对高度，未配置时以当前虚拟位置高度为闭环基准。
     */
    @SerializedName("cruise_altitude") private Double cruiseAltitude;
    /** 目标更新方式；默认 FIXED_SNAPSHOT，阶段 B 只接受该值。 */
    @SerializedName("target_update_mode") private String targetUpdateMode = "FIXED_SNAPSHOT";
    /** 恢复超时策略；默认 DISCARD，阶段 B 只接受该值。 */
    @SerializedName("on_restore_timeout") private String onRestoreTimeout = "DISCARD";

    /** @return 武器是否显式允许进入虚拟中段。 */
    public boolean isEnabled() { return enabled; }
    /** @return 钳制到非负数的最小真实飞行 Tick。 */
    public int getEntryMinFlightTick() { return Math.max(entryMinFlightTick, 0); }
    /** @return 钳制到非负数的最小离发射点距离，单位格。 */
    public double getEntryMinDistanceFromLaunch() { return Math.max(entryMinDistanceFromLaunch, 0.0); }
    /** @return 允许进入虚拟态时距目标的最小距离，单位格。 */
    public double getEntryMinTargetDistance() { return Math.max(entryMinTargetDistance, 0.0); }
    /** @return 固定恢复触发距离，单位格。 */
    public double getRestoreTargetDistance() { return Math.max(restoreTargetDistance, 0.0); }
    /** @return 按当前水平速度提前恢复的 Tick 数。 */
    public int getRestoreLeadTick() { return Math.max(restoreLeadTick, 0); }
    /** @return 钳制到 0～2 的恢复区块半径。 */
    public int getRestoreTicketRadius() { return Math.max(0, Math.min(restoreTicketRadius, 2)); }
    /** @return 至少为 1 的恢复等待超时 Tick。 */
    public int getRestoreWaitTimeoutTick() { return Math.max(restoreWaitTimeoutTick, 1); }
    /** 阶段 B 仍逐逻辑 Tick 积分；保留字段是为了后续 schema 演进。 */
    public int getVirtualUpdateIntervalTick() { return 1; }
    /** @return 至少为 1 的单次虚拟飞行 Tick 上限。 */
    public int getMaxVirtualFlightTick() { return Math.max(maxVirtualFlightTick, 1); }
    /** @return 可选世界 Y 巡航高度；未配置或值非有限时为 null。 */
    public Double getCruiseAltitude() {
        return cruiseAltitude != null && Double.isFinite(cruiseAltitude) ? cruiseAltitude : null;
    }
    /** @return 目标模式是否为阶段 B 支持的固定快照。 */
    public boolean usesFixedSnapshotTarget() { return "FIXED_SNAPSHOT".equalsIgnoreCase(targetUpdateMode); }
    /** @return 恢复超时策略是否为阶段 B 唯一安全策略 DISCARD。 */
    public boolean discardsOnRestoreTimeout() { return "DISCARD".equalsIgnoreCase(onRestoreTimeout); }
}
