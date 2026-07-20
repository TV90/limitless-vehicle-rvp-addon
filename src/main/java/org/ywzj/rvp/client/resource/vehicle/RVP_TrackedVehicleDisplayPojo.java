package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import org.ywzj.vehicle.client.resource.vehicle.TrackedVehicleDisplayPojo;

public class RVP_TrackedVehicleDisplayPojo extends TrackedVehicleDisplayPojo {

    @SerializedName("bedrock_backend")
    public String bedrockBackend = "vehicle";
}
