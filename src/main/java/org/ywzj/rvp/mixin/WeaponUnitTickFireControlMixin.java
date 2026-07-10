package org.ywzj.rvp.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.entity.PartEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.api.entity.SightObstruction;
import org.ywzj.vehicle.api.entity.TargetObstruction;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.Optional;

/**
 * Lets RVP homing weapons participate in the base auto-lock flow and adds a small IR hysteresis band
 * so edge-of-FOV locks do not chatter between lose/reacquire every few ticks.
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitTickFireControlMixin {

    @Unique private static final int ywzj_rvp$IR_RELOCK_GRACE_TICKS = 6;
    @Unique private static final float ywzj_rvp$IR_RELOCK_FOV_MARGIN_DEG = 1.25f;

    @Unique private Entity ywzj_rvp$lastIrLockedEntity;
    @Unique private int ywzj_rvp$lastIrLockTick = Integer.MIN_VALUE;

    @OnlyIn(Dist.CLIENT)
    @Inject(method = "tickFireControl", at = @At("TAIL"), remap = false)
    private void rvp$onTickFireControl(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (self.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
            RVP_RadarRoleHelper.tickPendingRadarLock(self);
            rvp$restoreExternalRadarLock(self);
        }

        if (!self.isSeekerOn()) {
            rvp$clearIrLockMemory();
            return;
        }

        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isEmpty()) {
            rvp$clearIrLockMemory();
            return;
        }
        AbstractVehicleWeapon<?> resolved = RVP_LaserWeapons.unwrap(weaponOpt.get());
        if (!(resolved instanceof RVP_WeaponBase rvpWeapon)) {
            rvp$clearIrLockMemory();
            return;
        }
        if (!rvpWeapon.getData().isHomingProjectile()) {
            rvp$clearIrLockMemory();
            return;
        }
        if (rvpWeapon.getData().isAntiRadiationMissile()) {
            if (self.getLockedEntity() != null) {
                self.setLockedEntity(null);
            }
            rvp$clearIrLockMemory();
            return;
        }

        WeaponUnitData.FireControlSensorType sensorType = self.getFireControlSensorType();
        if (sensorType == WeaponUnitData.FireControlSensorType.IR && self.getLockedEntity() != null) {
            ywzj_rvp$lastIrLockedEntity = self.getLockedEntity();
            ywzj_rvp$lastIrLockTick = self.getVehicle().tickCount;
            return;
        }
        if (self.getLockedEntity() != null) {
            return;
        }
        if (self.getLockCoolingTick() <= 20) {
            return;
        }

        Entity entity = null;
        if (sensorType == WeaponUnitData.FireControlSensorType.IR) {
            float fov = 30f;
            var stages = rvpWeapon.getData().getGuidanceData().getStages();
            if (stages != null && !stages.isEmpty()) {
                fov = stages.get(0).getSeeker().getFov();
            }
            entity = rvp$resolveIrLockWithHysteresis(self, fov);
        } else if (sensorType == WeaponUnitData.FireControlSensorType.RF) {
            RadarUnit radar = RVP_RadarRoleHelper.getPreferredLockRadar(self);
            if (radar != null) {
                entity = Radar.findTarget(radar, 90, self);
            }
        }

        if (entity != null) {
            self.setLockedEntity(entity);
        }
    }

    @Unique
    private Entity rvp$resolveIrLockWithHysteresis(WeaponUnit self, float fov) {
        if (ywzj_rvp$lastIrLockedEntity != null
                && ywzj_rvp$lastIrLockedEntity.isAlive()
                && self.getVehicle().tickCount - ywzj_rvp$lastIrLockTick <= ywzj_rvp$IR_RELOCK_GRACE_TICKS
                && rvp$isIrTargetStillAcceptable(self, ywzj_rvp$lastIrLockedEntity, fov + ywzj_rvp$IR_RELOCK_FOV_MARGIN_DEG)) {
            return ywzj_rvp$lastIrLockedEntity;
        }
        Entity found = Infrared.findTarget(self, fov);
        if (found != null) {
            ywzj_rvp$lastIrLockedEntity = found;
            ywzj_rvp$lastIrLockTick = self.getVehicle().tickCount;
        }
        return found;
    }

    @Unique
    private boolean rvp$isIrTargetStillAcceptable(WeaponUnit weaponUnit, Entity target, float fovDeg) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        Vec3 checkStart = weaponUnit.worldPivotPosition();
        Vec3 vLock = target.getBoundingBox().getCenter().subtract(checkStart);
        Vec3 vAim = weaponUnit.worldVec();
        if (Math.toDegrees(VectorUtil.angleBetween(vLock, vAim)) > fovDeg) {
            return false;
        }

        Level level = target.level();
        var vehicle = weaponUnit.getVehicle();
        Vec3 checkEnd = target.position();
        BlockHitResult result = level.clip(new ClipContext(checkStart, checkEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        if (result.getType() != HitResult.Type.MISS) {
            BlockPos pos = result.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() && state.canOcclude()) {
                return false;
            }
        }

        EntityHitResult entityHit = VectorUtil.hitEntity(vehicle, checkStart, checkEnd);
        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof SightObstruction) {
                return false;
            }
            if (entity instanceof TargetObstruction && entity != target && !(entity instanceof PartEntity<?>)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private void rvp$clearIrLockMemory() {
        ywzj_rvp$lastIrLockedEntity = null;
        ywzj_rvp$lastIrLockTick = Integer.MIN_VALUE;
    }

    @OnlyIn(Dist.CLIENT)
    private static void rvp$restoreExternalRadarLock(WeaponUnit self) {
        WeaponUnit root = self.getRootParentWeaponUnit();
        if (root != self || self.getLockedEntity() != null) {
            return;
        }
        if (LocalVehiclePlayer.instance.getVehicle() != self.getVehicle()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity externalLocked = RVP_ExternalRadarLinkHelper.getClientLockedEntity(
                self.getVehicle(),
                mc.level.dimension().location()
        );
        if (externalLocked != null && externalLocked.isAlive()) {
            self.setLockedEntity(externalLocked);
        }
    }
}
