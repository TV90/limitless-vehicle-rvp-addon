package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.client.resource.vehicle.TrackedVehicleDisplayPojo;

import java.util.List;
import java.util.Map;

public class RVP_TrackedVehicleDisplayPojo extends TrackedVehicleDisplayPojo {

    @SerializedName("bedrock_backend")
    public String bedrockBackend = "vehicle";

    @SerializedName("no_cull_bones")
    public List<String> noCullBones = List.of();

    @SerializedName("state_hidden_bones")
    public List<RVP_StateHiddenBone.Pojo> stateHiddenBones = List.of();

    @SerializedName("distance_hidden_bones")
    public List<RVP_DistanceHiddenBone.Pojo> distanceHiddenBones = List.of();

    /** 以扫描动画引用为键的启转条件，默认为空。 */
    @SerializedName("radar_scan_gates")
    public Map<String, RadarScanGate> radarScanGates = Map.of();

    public record RadarScanGate(
            /** 同一控制器声明的基座收拢动画引用，配合扫描动画使用。 */
            String switchable,
            /** 基座可扫描的最大收拢进度（秒），默认 0，基座完全展开时可扫描。 */
            @SerializedName("max_progress_s") float maxProgressSeconds,
            /** 扫描轨道首尾同姿态时的最远归位时间（秒），默认 0，使用原回放速度。 */
            @SerializedName("sweep_return_s") float sweepReturnSeconds
    ) {}

    @SerializedName("lod_models")
    public List<RVP_LodModel.Pojo> lodModels = List.of();
}
