package org.ywzj.rvp.weapon.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CGpsStateSync;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;

/**
 * Targeting pod weapon. It writes a server-authoritative target point that
 * missiles, bombs and dispensers can reuse.
 */
public class RVP_TargetingPodWeapon extends RVP_WeaponBase {

    public RVP_TargetingPodWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (isCoolingDown() || isReloading() || aimContexts.isEmpty()) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        if (shooter instanceof ServerPlayer player) {
            AimContext aim = aimContexts.get(0);
            Vec3 start = RVP_AimContexts.muzzle(aim);
            Vec3 look = Vec3.directionFromRotation(aim.direction.x, aim.direction.y).normalize();
            Vec3 end = start.add(look.scale(getData().getTargetingPodRange()));
            BlockHitResult blockHit = player.level().clip(new ClipContext(start, end,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, getVehicle()));
            Vec3 target = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
            var snapshot = GPSTargetManager.applyCurrentMode(player, player.level().dimension().location(), target);
            RVP_Network.CHANNEL.sendTo(
                    S2CGpsStateSync.of(snapshot),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        }
        return true;
    }
}
