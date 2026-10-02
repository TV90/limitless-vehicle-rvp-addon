package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;

import java.util.ArrayList;
import java.util.List;

public class RVP_GuidanceDataGPS extends RVP_GuidanceData {

    /** GPS 定位点落散布半径（CEP，格）；发射时对目标点一次性偏移。 */
    @SerializedName("gps_spread_radius")
    private float gpsSpreadRadius = 0f;

    /**
     * GPS 目标模式列表（{@code gps_modes}）：可选 SINGLE/MULTI/FAST/RADAR，忽略大小写、
     * 未知值丢弃、去重、最多取前 4 个；解析后为空或缺省 → 默认 [SINGLE, MULTI]（存量行为不变）。
     * FAST/RADAR 还受武器传感器门禁（FAST 要求 {@code rvp_fire_control_sensor_mode: "eo_ccip"}、
     * RADAR 要求 RF 传感器，见 {@code RVP_GpsModeSupport}），不满足时循环切换会跳过该模式。
     */
    @SerializedName("gps_modes")
    private List<String> gpsModes;

    /** MULTI 模式目标点上限（{@code multi_max_points}，默认 8）：标满后再标点为滑动窗口——新点变末位、其余前移、最旧丢弃。 */
    @SerializedName("multi_max_points")
    private int multiMaxPoints = 8;

    /** RADAR 模式在途改靶间隔（{@code radar_update_interval_second}，秒，默认 5）：雷达锁定期间按该周期刷新已发射弹的 GPS 目标点。 */
    @SerializedName("radar_update_interval_second")
    private float radarUpdateIntervalSecond = 5.0f;

    public float getGpsSpreadRadius() {
        return Math.max(gpsSpreadRadius, 0f);
    }

    /** 配置的 GPS 模式列表（已解析/去重/截断；空回退默认 [SINGLE, MULTI]）。 */
    public List<RVP_ClientGPSState.Mode> getConfiguredModes() {
        return resolveConfiguredModes(this);
    }

    /** MULTI 目标点上限；原始类型缺省 8，钳制 [1, 64]。 */
    public int getMultiMaxPoints() {
        if (multiMaxPoints < 1) {
            return 1;
        }
        return Math.min(multiMaxPoints, 64);
    }

    /** RADAR 在途改靶间隔（秒）；钳制 [0.5, 60]。 */
    public float getRadarUpdateIntervalSecond() {
        if (radarUpdateIntervalSecond < 0.5f) {
            return 0.5f;
        }
        return Math.min(radarUpdateIntervalSecond, 60.0f);
    }

    /**
     * 解析模式列表：字符串 → {@link RVP_ClientGPSState.Mode}（忽略大小写、未知丢弃、去重、
     * 最多 4 个）；空/缺省回退默认 [SINGLE, MULTI]。双端可用。
     */
    public static List<RVP_ClientGPSState.Mode> resolveConfiguredModes(@Nullable RVP_GuidanceData data) {
        if (!(data instanceof RVP_GuidanceDataGPS gps) || gps.gpsModes == null || gps.gpsModes.isEmpty()) {
            return List.of(RVP_ClientGPSState.Mode.SINGLE, RVP_ClientGPSState.Mode.MULTI);
        }
        List<RVP_ClientGPSState.Mode> out = new ArrayList<>();
        for (String raw : gps.gpsModes) {
            RVP_ClientGPSState.Mode mode = parseMode(raw);
            if (mode != null && !out.contains(mode)) {
                out.add(mode);
                if (out.size() >= 4) {
                    break;
                }
            }
        }
        if (out.isEmpty()) {
            return List.of(RVP_ClientGPSState.Mode.SINGLE, RVP_ClientGPSState.Mode.MULTI);
        }
        return List.copyOf(out);
    }

    /** MULTI 上限双端解析：非 GPS 数据实例回退默认 8。 */
    public static int resolveMultiMaxPoints(@Nullable RVP_GuidanceData data) {
        return data instanceof RVP_GuidanceDataGPS gps ? gps.getMultiMaxPoints() : 8;
    }

    /** RADAR 改靶间隔双端解析（秒）：非 GPS 数据实例回退默认 5。 */
    public static float resolveRadarUpdateIntervalSecond(@Nullable RVP_GuidanceData data) {
        return data instanceof RVP_GuidanceDataGPS gps ? gps.getRadarUpdateIntervalSecond() : 5.0f;
    }

    /** 模式字符串解析；null/未知返回 null（调用方丢弃）。 */
    @Nullable
    public static RVP_ClientGPSState.Mode parseMode(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SINGLE" -> RVP_ClientGPSState.Mode.SINGLE;
            case "MULTI" -> RVP_ClientGPSState.Mode.MULTI;
            case "FAST" -> RVP_ClientGPSState.Mode.FAST;
            case "RADAR" -> RVP_ClientGPSState.Mode.RADAR;
            default -> null;
        };
    }
}
