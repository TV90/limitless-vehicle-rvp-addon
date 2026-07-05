package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.ext.RadarUnitDataExt;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.custom.part.data.RadarUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ClientRadarAction;
import org.ywzj.vehicle.network.message.ServerVehicleWarn;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

@Mixin(value = RadarUnit.class, remap = false)
public class RadarUnitMixin {

    @Shadow(remap = false) private HashMap<Integer, RadarUnit.DetectedObject> detectedObjects;

    @Unique
    private int ywzj_rvp$scanTickCounter = 0;

    @Unique
    private RadarUnitData ywzj_rvp$getRadarData(RadarUnit self) {
        return (RadarUnitData) ((PartUnitAccessorMixin) (Object) self).ywzj_rvp$getData();
    }

    @Unique
    private boolean ywzj_rvp$isWithinScanHeight(RadarUnit self, Vec3 targetPos) {
        float minHeight = 25f;
        float maxHeight = 10000f;
        RadarUnitData data = ywzj_rvp$getRadarData(self);
        if (data instanceof RadarUnitDataExt ext) {
            minHeight = ext.ywzj_rvp$getScanMinHeight();
            maxHeight = ext.ywzj_rvp$getScanMaxHeight();
        }
        if (maxHeight < minHeight) {
            float t = minHeight;
            minHeight = maxHeight;
            maxHeight = t;
        }
        int groundY = self.getVehicle().level().getHeight(Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(targetPos.x), (int) Math.floor(targetPos.z));
        double heightAboveGround = targetPos.y - groundY;
        return heightAboveGround >= minHeight && heightAboveGround <= maxHeight;
    }

    private boolean ywzj_rvp$shouldSkipScan(RadarUnit self) {
        int period = 1;
        RadarUnitData data = ywzj_rvp$getRadarData(self);
        if (data instanceof RadarUnitDataExt ext) {
            int p = ext.ywzj_rvp$getScanPeriodTick();
            if (p > 0) period = p;
        }
        ywzj_rvp$scanTickCounter++;
        if (ywzj_rvp$scanTickCounter < period) {
            return true; // 跳过本次扫描
        }
        ywzj_rvp$scanTickCounter = 0;
        return false; // 执行扫描
    }

    @Unique
    private float ywzj_rvp$normalizeYawForLimits(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return yaw;
        }
        boolean prefer360Space = yMin >= 0.0f && yMax > 180.0f;
        if (prefer360Space && yaw < 0.0f) {
            return yaw + 360.0f;
        }
        return yaw;
    }

    @Unique
    private boolean ywzj_rvp$isYawWithin(float yaw, float yMin, float yMax) {
        if (yMax - yMin >= 360.0f) {
            return true;
        }
        return yaw >= yMin && yaw <= yMax;
    }

    @Unique
    private void ywzj_rvp$appendRvpAmmoTargets(RadarUnit self, List<Entity> entities, boolean requireTrackingLine) {
        Vec3 radarPos = self.worldRadarPosition();
        double maxScanDistance = self.getMaxScanDistance();
        double maxScanDistanceSqr = maxScanDistance * maxScanDistance;
        AABB scanBox = new AABB(radarPos.subtract(maxScanDistance, maxScanDistance, maxScanDistance),
                radarPos.add(maxScanDistance, maxScanDistance, maxScanDistance));
        Set<Integer> existingIds = new HashSet<>();
        for (Entity entity : entities) {
            existingIds.add(entity.getId());
        }
        List<RVP_BaseBullet> bullets = self.getVehicle().level().getEntitiesOfClass(RVP_BaseBullet.class, scanBox, bullet -> {
            if (bullet == null || !bullet.isAlive() || bullet.getVehicle() != null) {
                return false;
            }
            // 仅 signatureSize > 0 的弹体可被雷达探测
            float sig = bullet.getSignatureSize();
            if (sig <= 0) {
                return false;
            }
            RVP_EnumWeaponKind kind = bullet.getWeaponKind();
            if (kind != RVP_EnumWeaponKind.MISSILE && kind != RVP_EnumWeaponKind.BOMB) {
                return false;
            }
            // 使用 signatureSize 作为 RCS 倍率缩放有效探测距离
            double effectiveMaxSqr = maxScanDistanceSqr * sig * sig;
            Vec3 targetPos = bullet.getBoundingBox().getCenter();
            if (targetPos.distanceToSqr(radarPos) > effectiveMaxSqr) {
                return false;
            }
            if (!ywzj_rvp$isWithinScanHeight(self, targetPos)) {
                return false;
            }
            Vec2 aimRot = self.aimRot(targetPos);
            float yMin = self.getYRotMin();
            float yMax = self.getYRotMax();
            float y = ywzj_rvp$normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!ywzj_rvp$isYawWithin(y, yMin, yMax)) {
                return false;
            }
            if (requireTrackingLine && self.getYRotSpeed() > 0f && Math.abs(y - self.getYRot()) > self.getYRotSpeed() / 2.0f) {
                return false;
            }
            return !(Math.abs(aimRot.x - self.getXRot()) > self.getScanSectorAngle() / 2.0f);
        });
        for (RVP_BaseBullet bullet : bullets) {
            if (existingIds.add(bullet.getId())) {
                entities.add(bullet);
            }
        }
    }

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
            if (!ywzj_rvp$isWithinScanHeight(self, entityPos)) {
                return false;
            }
            Vec2 aimRot = self.aimRot(entityPos);
            float yMin = self.getYRotMin();
            float yMax = self.getYRotMax();
            float y = ywzj_rvp$normalizeYawForLimits((float) aimRot.y, yMin, yMax);
            if (!ywzj_rvp$isYawWithin(y, yMin, yMax)) {
                return false;
            }
            if (yRotSpeed > 0 && Math.abs(y - self.getYRot()) > yRotSpeed / 2.0f) {
                return false;
            }
            return !(Math.abs(aimRot.x - self.getXRot()) > self.getScanSectorAngle() / 2.0f);
        });
        ywzj_rvp$appendRvpAmmoTargets(self, entities, yRotSpeed > 0f);
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

        RadarUnitData data = ywzj_rvp$getRadarData(self);
        if (data instanceof RadarUnitDataExt ext) {
            int holdTick = ext.ywzj_rvp$getContactHoldTick();
            int scanPeriodTick = ext.ywzj_rvp$getScanPeriodTick();
            if (holdTick > 0) {
                lifeMillis = Math.max(holdTick * 50L, 100L);
            } else if (scanPeriodTick > 0) {
                // 默认 = scan_period_tick × 1.5（保证覆盖扫描间隔）
                lifeMillis = Math.max((long) (scanPeriodTick * 1.5 * 50L), 100L);
            } else {
                float yRotSpeed = self.getYRotSpeed();
                if (yRotSpeed > 0) {
                    float range = Math.min(360f, self.getYRotMax() - self.getYRotMin());
                    long scanCycleMillis = Math.max((long) (range / yRotSpeed / 20f * 1000L) * 2L, 100L);
                    lifeMillis = Math.max(scanCycleMillis * 2L, 100L);
                }
            }
            if (scanPeriodTick > 0) {
                warnIntervalTick = Math.max(2, scanPeriodTick);
            }
        }

        // [RVP] hold 期间目标位置预测 + 扇区外立即删除
        long finalTimeNow = timeNow;
        long finalLifeMillis = lifeMillis;
        float yRotMin = self.getYRotMin();
        float yRotMax = self.getYRotMax();
        float xRot = self.getXRot();
        float scanSectorHalf = self.getScanSectorAngle() / 2.0f;
        detectedObjects.values().removeIf(detectedObject -> {
            if (detectedObject.detectedTime + finalLifeMillis < finalTimeNow) {
                return true; // hold 超时，删除
            }
            // hold 期间：每 tick 用实体真实位置，实现连续平滑跟踪
            Entity targetEntity = detectedObject.entity;
            if (targetEntity != null && targetEntity.isAlive()) {
                AABB aabb = targetEntity.getBoundingBox();
                detectedObject.detectedPosition = aabb.getCenter();
            }
            if (!ywzj_rvp$isWithinScanHeight(self, detectedObject.detectedPosition)) {
                return true;
            }
            // 实体出扇区 → 立即删除
            Vec2 aimRot = self.aimRot(detectedObject.detectedPosition);
            float y = ywzj_rvp$normalizeYawForLimits((float) aimRot.y, yRotMin, yRotMax);
            if (!ywzj_rvp$isYawWithin(y, yRotMin, yRotMax)
                    || Math.abs(aimRot.x - xRot) > scanSectorHalf) {
                return true;
            }
            return false;
        });

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

    @Inject(method = "tickDetect", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$tickDetect(CallbackInfo ci) {
        RadarUnit self = (RadarUnit) (Object) this;
        RadarUnitData data = ywzj_rvp$getRadarData(self);
        if (!(data instanceof RadarUnitDataExt ext)) {
            return;
        }

        WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (weaponUnit == null) {
            ci.cancel();
            return;
        }
        if (LocalVehiclePlayer.instance.getPlayer() != weaponUnit.getOwner()) {
            ci.cancel();
            return;
        }

        boolean phaseMode = "phase".equalsIgnoreCase(ext.ywzj_rvp$getScanAnimationMode());
        if (phaseMode && ywzj_rvp$shouldSkipScan(self)) {
            ci.cancel();
            return;
        }

        Vec3 radarPos = self.worldRadarPosition();
        List<Entity> entities = phaseMode
                ? Radar.scanTargets(self.getVehicle(), radarPos, self.getMaxScanDistance(), entityPos -> {
                    if (!ywzj_rvp$isWithinScanHeight(self, entityPos)) {
                        return false;
                    }
                    Vec2 aimRot = self.aimRot(entityPos);
                    return !(aimRot.y < self.getYRotMin()) && !(aimRot.y > self.getYRotMax())
                            && !(Math.abs(aimRot.x - self.getXRot()) > self.getScanSectorAngle() / 2.0f);
                })
                : Radar.detectTargets(self.getVehicle(), radarPos, self.getMaxScanDistance(), entityPos -> {
                    if (!ywzj_rvp$isWithinScanHeight(self, entityPos)) {
                        return false;
                    }
                    Vec2 aimRot = self.aimRot(entityPos);
                    return !(aimRot.y < self.getYRotMin()) && !(aimRot.y > self.getYRotMax())
                            && !(Math.abs(aimRot.y - self.getYRot()) > self.getYRotSpeed() / 2.0f)
                            && !(Math.abs(aimRot.x - self.getXRot()) > self.getScanSectorAngle() / 2.0f);
                });
        ywzj_rvp$appendRvpAmmoTargets(self, entities, !phaseMode);
        entities.forEach(self::detect);

        for (RadarUnit.DetectedObject detectedObject : self.getDetectedEntities().values()) {
            ClientRadarAction action = new ClientRadarAction();
            action.action = ClientRadarAction.Action.DETECT;
            action.toEntityId = detectedObject.entity.getId();
            Channel.CHANNEL.sendToServer(action);
        }

        ci.cancel();
    }

    @Inject(method = "toggle", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$onToggle(Boolean on, CallbackInfo ci) {
        RadarUnit self = (RadarUnit) (Object) this;
        if (self.isOn()) {
            org.ywzj.rvp.client.gui.RadarEnabledTickHelper.setEnabledTick(self, self.getVehicle().tickCount);
        }
    }
}
