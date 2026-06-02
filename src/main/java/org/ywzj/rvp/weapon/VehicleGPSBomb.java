package org.ywzj.rvp.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.rvp.entity.weapon.GPSBombEntity;
import org.ywzj.rvp.weapon.gps.GPSTarget;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.rvp.weapon.data.VehicleGPSBombWeaponData;

import java.util.List;

public class VehicleGPSBomb extends AbstractVehicleWeapon<VehicleGPSBombWeaponData> {

    public VehicleGPSBomb(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleGPSBombWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isCoolingDown() || isReloading() || !consumeAmmo(aimContexts)) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();

        if (shooter == null) {
            return false;
        }

        var vehicle = getVehicle();
        var data = this.getData();

        GPSTarget gpsTarget = GPSTargetManager.get(shooter);
        Vec3 targetPos = null;
        ResourceLocation dim = shooter.level().dimension().location();
        if (gpsTarget != null) {
            if (dim.equals(gpsTarget.dimension)) {
                targetPos = gpsTarget.pos;
            } else if (shooter instanceof ServerPlayer serverPlayer) {
                serverPlayer.displayClientMessage(Component.translatable("message.ywzj_rvp.gps_bomb.wrong_dimension"), true);
            }
        }

        for (AimContext aimContext : aimContexts) {
            GPSBombEntity bombEntity = new GPSBombEntity(RvpEntities.GPS_BOMB.get(), vehicle.level(), data.getWeaponId());
            bombEntity.damage = data.getDamage();
            bombEntity.explosion = data.getExplosion();
            bombEntity.life = data.getLife();
            bombEntity.gravityScale = data.getGravityScale();
            bombEntity.fuseDelayTick = data.getFuseDelayTick();
            bombEntity.targetPos = targetPos;
            bombEntity.terminalIrEnabled = data.isTerminalIrEnabled();
            bombEntity.terminalIrActivationDistance = data.getTerminalIrActivationDistance();
            bombEntity.terminalIrSeekerFov = data.getTerminalIrSeekerFov();
            bombEntity.terminalIrSeekRange = data.getTerminalIrSeekRange();
            bombEntity.terminalIrScanIntervalTick = data.getTerminalIrScanIntervalTick();
            bombEntity.terminalIrVehicleOnly = data.isTerminalIrVehicleOnly();
            bombEntity.terminalIrSmokeBreakLock = data.isTerminalIrSmokeBreakLock();
            bombEntity.terminalIrMemoryTick = data.getTerminalIrMemoryTick();
            bombEntity.terminalIrAllowReacquire = data.isTerminalIrAllowReacquire();
            bombEntity.shoot(this.getVehicle(), this.getDisplayName(),
                    aimContext.position, aimContext.direction.x, aimContext.direction.y,
                    this.getWeaponUnit().getOwner());
            vehicle.level().addFreshEntity(bombEntity);
            vehicle.physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
        }
        return true;
    }
}
