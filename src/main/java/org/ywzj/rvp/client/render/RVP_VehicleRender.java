package org.ywzj.rvp.client.render;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;

public class RVP_VehicleRender<T extends AbstractVehicle> extends VehicleRender<T> {

    public RVP_VehicleRender(EntityRendererProvider.Context context) {
        super(context);
    }
}
