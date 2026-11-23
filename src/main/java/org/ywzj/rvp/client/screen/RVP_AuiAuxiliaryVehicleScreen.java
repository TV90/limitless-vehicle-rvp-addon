package org.ywzj.rvp.client.screen;

import net.minecraft.client.gui.screens.Screen;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 从已有辅助载具入口直接打开改装页的相应栏目。 */
public final class RVP_AuiAuxiliaryVehicleScreen extends RVP_AuiVariantScreen {
    public RVP_AuiAuxiliaryVehicleScreen(AbstractVehicle vehicle, Screen parent) {
        super(vehicle, parent, true);
    }
}
