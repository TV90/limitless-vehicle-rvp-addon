package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.client.resource.vehicle.FixedWingVehicleDisplayPojo;

public class RVP_FixedWingVehicleDisplayPojo extends FixedWingVehicleDisplayPojo {

    @SerializedName("bedrock_backend")
    public String bedrockBackend = "vehicle";
}
