package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CSetTVMissile;
import org.ywzj.rvp.weapon.data.RVP_EnumFireMode;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.rvp.weapon.util.RVP_CanisterGridUtil;
import org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadShape;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Projectile-spawning weapon for missile, rocket, machinegun and bomb entries.
 */
public class RVP_ProjectileWeapon extends RVP_WeaponBase {

    private final Supplier<EntityType<? extends Projectile>> entityType;

    private List<AimContext> burstVolleyAimContexts;
    private LivingEntity burstVolleyShooter;

    public RVP_ProjectileWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data,
                                String serializeId, Supplier<EntityType<? extends Projectile>> entityType) {
        super(vehicle, unit, index, data, serializeId);
        this.entityType = entityType;
    }

    @Override
    public void tick() {
        super.tick();
        if (getVehicle().level().isClientSide()) {
            return;
        }
        if (getData().getFireData().getFireMode() != RVP_EnumFireMode.BURST) {
            return;
        }
        getFireController().tickBurstVolley(this::fireBurstVolleyShot);
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isReloading()) {
            return false;
        }
        RVP_FireData fire = getData().getFireData();
        if (fire.getFireMode() == RVP_EnumFireMode.BURST) {
            return shootBurstRound(aimContexts, shooter, fire);
        }
        if (isCoolingDown() || !consumeAmmo(aimContexts)) {
            return false;
        }
        getFireController().primeServerShot();
        if (!canShootOnServer()) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        dispatchShots(aimContexts, shooter, consumeChargeScale());
        getFireController().onShotFired();
        return true;
    }

    /**
     * 一轮点射：立即打出第一发，其余按 {@code shoot_interval} 在服务端 tick 中连发。
     */
    private boolean shootBurstRound(List<AimContext> aimContexts, LivingEntity shooter, RVP_FireData fire) {
        var controller = getFireController();
        if (!controller.canStartBurstRound(fire)) {
            return false;
        }
        if (isCoolingDown()) {
            return false;
        }
        if (!consumeAmmo(aimContexts)) {
            return false;
        }
        controller.primeServerShot();
        if (!canShootOnServer()) {
            return false;
        }

        burstVolleyAimContexts = new ArrayList<>(aimContexts);
        burstVolleyShooter = shooter;
        this.lastShootTime = System.currentTimeMillis();

        float chargeScale = consumeChargeScale();
        dispatchShots(aimContexts, shooter, chargeScale);

        int burstCount = fire.getBurstCount();
        if (burstCount > 1) {
            controller.startBurstVolleyAfterFirstShot(fire, getShootInterval());
        } else {
            controller.completeBurstRound(fire);
        }
        return true;
    }

    private void fireBurstVolleyShot() {
        if (burstVolleyAimContexts == null || burstVolleyShooter == null) {
            return;
        }
        if (!consumeAmmo(burstVolleyAimContexts)) {
            getFireController().completeBurstRound(getData().getFireData());
            return;
        }
        this.lastShootTime = System.currentTimeMillis();
        dispatchShots(burstVolleyAimContexts, burstVolleyShooter, consumeChargeScale());
    }

    private void dispatchShots(List<AimContext> aimContexts, LivingEntity shooter, float chargeScale) {
        RVP_WeaponData data = getData();
        var unit = getWeaponUnit().getRootParentWeaponUnit();
        var lock = data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE && data.isActiveRadar()
                ? null : unit.getLockedEntity();
        for (AimContext aim : aimContexts) {
            if (data.getFireData().isCanister()) {
                shootCanister(data, shooter, aim, lock, unit, chargeScale);
            } else {
                shootProjectiles(data, shooter, aim, lock, unit, chargeScale);
            }
            getVehicle().physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
        }
    }

    private void shootProjectiles(RVP_WeaponData data, LivingEntity shooter, AimContext aim,
                                  net.minecraft.world.entity.Entity lock, WeaponUnit unit, float chargeScale) {
        int totalProjectiles = data.getFireData().getCanisterCount() * data.getFireData().getCanisterBurstCount();
        for (int i = 0; i < totalProjectiles; i++) {
            RVP_BaseBullet projectile = RVP_ProjectileSpawner.spawn(data, data.getWeaponKind(), entityType,
                    getVehicle(), shooter, aim, lock, unit, chargeScale, 0f);
            maybeEnterTV(data, shooter, projectile, i);
        }
    }

    private void shootCanister(RVP_WeaponData data, LivingEntity shooter, AimContext aim,
                               net.minecraft.world.entity.Entity lock, WeaponUnit unit, float chargeScale) {
        RVP_FireData fire = data.getFireData();
        int pellets = Math.max(fire.getCanisterCount(), 1);
        int total = pellets * fire.getCanisterBurstCount();
        int[][] gridCells = fire.getCanisterShape() == RVP_EnumSpreadShape.SQUARE
                ? RVP_CanisterGridUtil.assignCells(pellets, fire.getCanisterDistribution())
                : null;
        float spread = data.getInaccuracy();
        float[] centerOffset = new float[]{0f, 0f};
        for (int i = 0; i < total; i++) {
            int pelletIndex = i % pellets;
            if (pelletIndex == 0) {
                centerOffset = RVP_ProjectileSpawner.sampleSpreadCenter(getVehicle().level(), spread);
            }
            AimContext pelletAim = canisterAim(aim, pelletIndex, gridCells, fire, centerOffset);
            RVP_BaseBullet projectile = RVP_ProjectileSpawner.spawn(data, data.getWeaponKind(), entityType,
                    getVehicle(), shooter, pelletAim, lock, unit, chargeScale, 0f, false);
            maybeEnterTV(data, shooter, projectile, i);
        }
    }

    private AimContext canisterAim(AimContext base, int pelletIndex, int[][] gridCells, RVP_FireData fire,
                                   float[] centerOffset) {
        AimContext out = new AimContext();
        Vec3 muzzle = RVP_AimContexts.muzzle(base);
        out.from = muzzle;
        out.position = base.position;
        float xRot = base.direction.x + centerOffset[0];
        float yRot = base.direction.y + centerOffset[1];
        float diff = fire.getCanisterDiff();
        int type = fire.getCanisterType();
        var random = getVehicle().level().random;
        var distribution = fire.getCanisterDistribution();
        var footprint = fire.getCanisterShape();
        boolean useGrid = gridCells != null && footprint == RVP_EnumSpreadShape.SQUARE;

        if (type == 0) {
            float[] offset = new float[3];
            if (useGrid) {
                RVP_SpreadDistributionUtil.sampleCanisterGridPosition(
                        gridCells[pelletIndex], fire.getCanisterCount(), diff, offset);
            } else {
                RVP_SpreadDistributionUtil.sampleCanisterPosition(random, distribution, footprint, diff, offset);
            }
            out.from = muzzle.add(offset[0], offset[1], offset[2]);
        } else {
            float[] angular = new float[2];
            if (useGrid) {
                RVP_SpreadDistributionUtil.sampleCanisterGridAngular(
                        gridCells[pelletIndex], fire.getCanisterCount(), diff, angular);
            } else {
                RVP_SpreadDistributionUtil.sampleCanisterAngular(random, distribution, footprint, diff, angular);
            }
            xRot += angular[0];
            yRot += angular[1];
            if (type == 2 && fire.getCanisterBurstDelayTime() > 0f) {
                Vec3 dir = VectorUtil.rotToVec(xRot, yRot).normalize();
                out.from = muzzle.add(dir.scale(pelletIndex * fire.getCanisterBurstDelayTime()));
            }
        }

        out.direction = new Vec2(xRot, yRot);
        return out;
    }

    private void maybeEnterTV(RVP_WeaponData data, LivingEntity shooter, RVP_BaseBullet projectile, int projectileIndex) {
        if (projectile instanceof RVP_MissileEntity && projectileIndex == 0 && data.usesGuidanceType(RVP_EnumGuidanceType.TV)
                && !getVehicle().level().isClientSide()
                && shooter instanceof net.minecraft.server.level.ServerPlayer player) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), S2CSetTVMissile.set(projectile.getId()));
        }
    }
}
