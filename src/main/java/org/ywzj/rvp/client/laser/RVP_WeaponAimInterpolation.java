package org.ywzj.rvp.client.laser;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

/**
 * Interpolates weapon aim between ticks for smooth client-side laser rendering.
 */
public final class RVP_WeaponAimInterpolation {

    private RVP_WeaponAimInterpolation() {}

    public static AimContext aimContext(WeaponUnit unit, AbstractVehicle vehicle, float partialTick) {
        if (partialTick >= 1.0F || partialTick <= 0.0F) {
            return offsetForVehicleLerp(unit.aimContext(), vehicle, partialTick);
        }

        float savedX = unit.getXRot();
        float savedY = unit.getYRot();
        try {
            unit.setXRot(Mth.rotLerp(partialTick, unit.xRotO, unit.getXRot()));
            unit.setYRot(Mth.rotLerp(partialTick, unit.yRotO, unit.getYRot()));
            unit.updateRot();
            return offsetForVehicleLerp(unit.aimContext(), vehicle, partialTick);
        } finally {
            unit.setXRot(savedX);
            unit.setYRot(savedY);
            unit.updateRot();
        }
    }

    private static AimContext offsetForVehicleLerp(AimContext aim, AbstractVehicle vehicle, float partialTick) {
        if (partialTick >= 1.0F) {
            return aim;
        }
        Vec3 tickPos = vehicle.position();
        Vec3 renderPos = vehicle.getPosition(partialTick);
        Vec3 delta = renderPos.subtract(tickPos);

        AimContext out = new AimContext();
        out.position = aim.position.add(delta);
        out.direction = new Vec2(aim.direction.x, aim.direction.y);
        return out;
    }
}
