package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.ext.RVPEraStateAccess;
import org.ywzj.rvp.network.S2CVehicleEraState;

public final class RVP_ClientVehicleEraState {

    private RVP_ClientVehicleEraState() {
    }

    public static void apply(S2CVehicleEraState msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(msg.entityId);
        if (entity instanceof RVPEraStateAccess access) {
            access.rvp$setInactiveEraBones(msg.inactiveBones);
        }
    }
}
