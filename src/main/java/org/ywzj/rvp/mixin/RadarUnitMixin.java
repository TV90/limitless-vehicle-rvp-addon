package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleWarn;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.HashMap;
import java.util.List;

@Mixin(value = RadarUnit.class, remap = false)
public class RadarUnitMixin {

    @Shadow(remap = false) private HashMap<Integer, RadarUnit.DetectedObject> detectedObjects;

    @Inject(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/RadarUnit;tickTargets()V", shift = At.Shift.BEFORE),
            remap = false
    )
    private void ywzj_rvp$serverScanForGunner(CallbackInfo ci) {
        RadarUnit self = (RadarUnit) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (!vehicle.hasPower() || !self.isOn()) {
            return;
        }
        if (!(vehicle.getDriver() instanceof GunnerEntity)) {
            return;
        }

        Vec3 radarPos = self.worldRadarPosition();
        double maxScanDistance = self.getMaxScanDistance();
        float yRotSpeed = self.getYRotSpeed();
        List<Entity> entities = Radar.scanTargets(vehicle, radarPos, maxScanDistance, entityPos -> {
            Vec2 aimRot = self.aimRot(entityPos);
            if (aimRot.y < self.getYRotMin() || aimRot.y > self.getYRotMax()) {
                return false;
            }
            if (yRotSpeed > 0 && Math.abs(aimRot.y - self.getYRot()) > yRotSpeed / 2.0f) {
                return false;
            }
            return !(Math.abs(aimRot.x - self.getXRot()) > self.getScanSectorAngle() / 2.0f);
        });
        for (Entity entity : entities) {
            self.detect(entity);
        }
    }

    @Inject(method = "tickTargets", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$tickTargets(CallbackInfo ci) {
        RadarUnit self = (RadarUnit) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();

        long timeNow = System.currentTimeMillis();
        long lifeMillis = 100L;
        int warnIntervalTick = 20;

        RadarUnitData data = (RadarUnitData) ((PartUnitAccessorMixin) (Object) self).ywzj_rvp$getData();
        if (data instanceof RadarUnitDataExt ext) {
            int holdTick = ext.ywzj_rvp$getContactHoldTick();
            if (holdTick > 0) {
                lifeMillis = Math.max(holdTick * 50L, 100L);
            } else {
                float yRotSpeed = self.getYRotSpeed();
                if (yRotSpeed > 0) {
                    float range = Math.min(360f, self.getYRotMax() - self.getYRotMin());
                    long scanCycleMillis = Math.max((long) (range / yRotSpeed / 20f * 1000L) * 2L, 100L);
                    lifeMillis = Math.max(scanCycleMillis * 2L, 100L);
                }
            }
            int scanPeriodTick = ext.ywzj_rvp$getScanPeriodTick();
            if (scanPeriodTick > 0) {
                warnIntervalTick = Math.max(2, scanPeriodTick);
            }
        }

        long finalTimeNow = timeNow;
        long finalLifeMillis = lifeMillis;
        detectedObjects.values().removeIf(detectedObject -> detectedObject.detectedTime + finalLifeMillis < finalTimeNow);

        if (!vehicle.level().isClientSide() && warnIntervalTick > 0 && vehicle.tickCount % warnIntervalTick == 0) {
            detectedObjects.values().forEach(detectedObject -> {
                if (detectedObject.entity instanceof AbstractVehicle toVehicle) {
                    ServerVehicleWarn serverVehicleWarn = new ServerVehicleWarn();
                    serverVehicleWarn.fromEntityId = vehicle.getId();
                    serverVehicleWarn.toEntityId = toVehicle.getId();
                    serverVehicleWarn.warnType = WarnType.RADAR_SEARCH;
                    serverVehicleWarn.info = self.getRadarType();
                    for (Entity entity : toVehicle.getPassengers()) {
                        if (entity instanceof ServerPlayer player) {
                            Channel.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), serverVehicleWarn);
                        }
                    }
                }
            });
        }

        ci.cancel();
    }
}
