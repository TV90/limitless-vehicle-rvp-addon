package org.ywzj.rvp.weapon.core;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

/**
 * {@link AimContext} helpers aligned with {@link org.ywzj.vehicle.vehicle.weapon.VehicleCannon} /
 * {@link org.ywzj.vehicle.vehicle.weapon.VehicleRocket}: {@code from} = muzzle, {@code position} = CCIP / crosshair hit.
 */
public final class RVP_AimContexts {

    private RVP_AimContexts() {}

    /** Muzzle world position; matches {@code aimContext.from} on official weapons. */
    public static Vec3 muzzle(AimContext aim) {
        if (aim.from != null) {
            return aim.from;
        }
        if (aim.position != null) {
            return aim.position;
        }
        return Vec3.ZERO;
    }

    @Nullable
    public static Vec3 impactPoint(AimContext aim) {
        return aim.position;
    }

    /** 空炮闩武器站仅用观瞄位置和方向计算显示落点。 */
    public static Vec3 opticalSightHit(WeaponUnit unit, Vec3 start, float partialTick) {
        // 调用本体的姿态换算，使用当前帧的武器站朝向。
        float pitch = Mth.rotLerp(partialTick, unit.xRotO, unit.getXRot());
        float yaw = Mth.rotLerp(partialTick, unit.yRotO, unit.getYRot());
        Vec3 direction = unit.worldVec(pitch, yaw).normalize();
        // 沿用本体瞄准落点查询的 1024 格距离和遮挡规则。
        return VectorUtil.hitPosition(unit.getVehicle(), start, start.add(direction.scale(1024.0)));
    }
}
