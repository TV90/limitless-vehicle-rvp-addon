package org.ywzj.rvp.weapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;
import org.ywzj.rvp.network.RvpNetwork;
import org.ywzj.rvp.network.S2CSetTVMissile;
import org.ywzj.rvp.weapon.data.VehicleTVMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import java.util.List;

public class VehicleTVMissile extends AbstractVehicleWeapon<VehicleTVMissileWeaponData> {
    public VehicleTVMissile(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleTVMissileWeaponData data, String serializeId) {
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
        for (AimContext aimContext : aimContexts) {
            TVMissileEntity missile = new TVMissileEntity(RvpEntities.TV_MISSILE.get(), vehicle.level(), data, getWeaponUnit().getRootParentWeaponUnit());
            missile.shoot(vehicle, getDisplayName(),
                    aimContext.position, aimContext.direction.x, aimContext.direction.y,
                    getWeaponUnit().getOwner());
            missile.setDeltaMovement(missile.getDeltaMovement().add(vehicle.getDeltaMovement()));
            vehicle.level().addFreshEntity(missile);
            vehicle.physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
            if (!vehicle.level().isClientSide() && shooter instanceof ServerPlayer sp) {
                RvpNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), S2CSetTVMissile.set(missile.getId()));
            }
        }
        return true;
    }

    @Override
    public boolean withSeeker() {
        return true;
    }
}
