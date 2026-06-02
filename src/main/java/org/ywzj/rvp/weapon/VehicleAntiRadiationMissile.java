package org.ywzj.rvp.weapon;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.rvp.entity.weapon.AntiRadiationMissileEntity;
import org.ywzj.rvp.weapon.data.VehicleAntiRadiationMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.List;

public class VehicleAntiRadiationMissile extends AbstractVehicleWeapon<VehicleAntiRadiationMissileWeaponData> {

    private int preselectedVehicleId = -1;
    private int preselectedRadarIndex = -1;
    @Nullable
    private Vec3 preselectedPos;

    public VehicleAntiRadiationMissile(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleAntiRadiationMissileWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    public void setPreselect(int vehicleId, int radarIndex, @Nullable Vec3 pos) {
        this.preselectedVehicleId = vehicleId;
        this.preselectedRadarIndex = radarIndex;
        this.preselectedPos = pos;
    }

    public void clearPreselect() {
        this.preselectedVehicleId = -1;
        this.preselectedRadarIndex = -1;
        this.preselectedPos = null;
    }

    public int getPreselectedVehicleId() {
        return preselectedVehicleId;
    }

    public int getPreselectedRadarIndex() {
        return preselectedRadarIndex;
    }

    @Nullable
    public Vec3 getPreselectedPos() {
        return preselectedPos;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean doClientShoot() {
        VehicleAntiRadiationMissileWeaponData data = getData();
        if (!data.isAntiRadiationAllowFireWithoutSeeker()) {
            boolean hasPreselect = preselectedVehicleId >= 0 && preselectedRadarIndex >= 0;
            if (!hasPreselect && getWeaponUnit().getLockedEntity() == null) {
                return false;
            }
        }
        return super.doClientShoot();
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

        VehicleAntiRadiationMissileWeaponData data = this.getData();
        if (!data.isAntiRadiationAllowFireWithoutSeeker()) {
            boolean hasPreselect = preselectedVehicleId >= 0 && preselectedRadarIndex >= 0;
            if (!hasPreselect && getWeaponUnit().getLockedEntity() == null) {
                if (shooter.level().isClientSide()) {
                    return false;
                }
                if (shooter instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(Component.translatable("message.ywzj_rvp.anti_radiation.no_emitter"), true);
                }
                return false;
            }
        }

        var vehicle = getVehicle();
        for (AimContext aimContext : aimContexts) {
            AntiRadiationMissileEntity missile = new AntiRadiationMissileEntity(
                    RvpEntities.ANTI_RADIATION_MISSILE.get(),
                    vehicle.level(),
                    data,
                    getWeaponUnit(),
                    preselectedVehicleId,
                    preselectedRadarIndex,
                    preselectedPos
            );
            missile.damage = data.getDamage();
            missile.headShot = data.getHeadshotMultiplier();
            missile.explosion = data.getExplosion();
            missile.life = data.getLife();
            missile.shoot(vehicle, getDisplayName(),
                    aimContext.position, aimContext.direction.x, aimContext.direction.y,
                    getWeaponUnit().getOwner());
            vehicle.level().addFreshEntity(missile);
            vehicle.physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
        }
        return true;
    }

    @Override
    public boolean withSeeker() {
        return true;
    }
}
