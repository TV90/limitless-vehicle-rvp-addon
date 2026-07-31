package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplayPojo;

import java.util.List;

public class RVP_BaseDisplayPojo extends BaseDisplayPojo {

    @SerializedName("bedrock_backend")
    public String bedrockBackend = "vehicle";

    @SerializedName("no_cull_bones")
    public List<String> noCullBones = List.of();
}
