package org.ywzj.rvp.client.resource.vehicle;

import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

/** 供批量维护渲染规则的代码读取已就绪的模型。 */
public interface RVP_DeferredDisplay {
    @Nullable VehicleBedrockModel getLoadedModel();

    @Nullable
    static VehicleBedrockModel loadedModel(BaseDisplay display) {
        return display instanceof RVP_DeferredDisplay deferred ? deferred.getLoadedModel() : display.getModel();
    }
}
