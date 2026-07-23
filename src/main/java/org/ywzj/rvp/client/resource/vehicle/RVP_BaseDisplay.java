package org.ywzj.rvp.client.resource.vehicle;

import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;

public class RVP_BaseDisplay extends BaseDisplay {

    protected final RVP_BedrockBackend bedrockBackend;

    public RVP_BaseDisplay(RVP_BaseDisplayPojo pojo) {
        this(pojo, RVP_BedrockBackend.fromString(pojo.bedrockBackend));
    }

    protected RVP_BaseDisplay(RVP_BaseDisplayPojo pojo, RVP_BedrockBackend backend) {
        super(pojo);
        this.bedrockBackend = backend == null ? RVP_BedrockBackend.VEHICLE : backend;
    }

    public RVP_BedrockBackend getBedrockBackend() {
        return bedrockBackend;
    }
}
