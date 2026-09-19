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
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
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
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (self.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
            RVP_RadarRoleHelper.tickPendingRadarLock(self);
            rvp$restoreExternalRadarLock(self, weaponOpt.orElse(null));
        }

        if (!self.isSeekerOn()) {
            rvp$clearIrLockMemory();
            return;
        }

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
        if (sensorType == WeaponUnitData.FireControlSensorType.IR
                || RVP_IrLockHelper.usesIrAcquireOnEo(sensorType, rvpWeapon.getData())) {
            entity = rvp$resolveIrLockWithHysteresis(self, rvpWeapon);
        } else if (sensorType == WeaponUnitData.FireControlSensorType.RF) {
            // 雷达自动锁定（TWS 扫描跟踪）：ARH 等通过雷达 TWS 数据链制导，保持自动锁定
            // （模拟现实中雷达边扫描边跟踪给主动弹提供数据链目标更新）；
            // SARH 半主动雷达弹需要载具【锁定】目标才制导，扫描阶段不自动锁定，
            // 避免"只开导引头、雷达照射到目标"时 SARH 未手动锁定也能获得制导。
            if (!rvpWeapon.getData().usesGuidanceType(RVP_EnumGuidanceType.SARH)) {
                RadarUnit radar = RVP_RadarRoleHelper.getPreferredLockRadar(self);
                if (radar != null) {
                    entity = Radar.findTarget(radar, 90, self);
                }
                // IR 发射武器的 RF 供锁补离轴校验（2026-09-20 离轴角 bug 修复，bug B 治本）：
                // 雷达 TWS 锥（90°）远大于 IR 导引头离轴角，直接落锁会让 IR 弹获得超锥锁
                //（发射授权的离轴终检会兜底拒射，这里在锁建立时就拦掉，语义更干净）。
                // ARH 等雷达制导弹不受 IR 离轴角约束（isIrLaunchWeapon 已排除雷达/反辐射/GPS）。
                if (entity != null && !RVP_IrLockHelper.isLaunchTargetWithinOffAxis(
                        self, entity, rvpWeapon.getData(), 0f)) {
                    entity = null;
                }
            }
        }

        if (entity != null) {
            self.setLockedEntity(entity);
        }
    }

    @Inject(method = "tickFireControl", at = @At("TAIL"), remap = false)
    private void rvp$restoreServerExternalRadarLock(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (self.getVehicle().level().isClientSide()) {
            return;
        }
        if (self.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            return;
        }
        RVP_RadarRoleHelper.tickPendingRadarLock(self);
        WeaponUnit root = self.getRootParentWeaponUnit();
        if (root != self || self.getLockedEntity() != null) {
            return;
        }
        int externalLockedId = RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root);
        if (externalLockedId == Integer.MIN_VALUE) {
            return;
        }
        Entity externalLocked = self.getVehicle().level().getEntity(externalLockedId);
        if (externalLocked != null && externalLocked.isAlive()) {
            self.setLockedEntity(externalLocked);
        }
    }

    @Unique
    private Entity rvp$resolveIrLockWithHysteresis(WeaponUnit self, RVP_WeaponBase rvpWeapon) {
        var data = rvpWeapon.getData();
        if (ywzj_rvp$lastIrLockedEntity != null
                && ywzj_rvp$lastIrLockedEntity.isAlive()
                && self.getVehicle().tickCount - ywzj_rvp$lastIrLockTick <= ywzj_rvp$IR_RELOCK_GRACE_TICKS
                && RVP_IrLockHelper.isTargetWithinAcquireLimits(
                        self,
                        ywzj_rvp$lastIrLockedEntity,
                        data,
                        ywzj_rvp$IR_RELOCK_FOV_MARGIN_DEG
                )) {
            return ywzj_rvp$lastIrLockedEntity;
        }
        Entity found = Infrared.findTarget(self, RVP_IrLockHelper.halfAngleFromFull(data.resolveLaunchSeekerFullFov()));
        if (found != null && RVP_IrLockHelper.isTargetWithinAcquireLimits(self, found, data)) {
            ywzj_rvp$lastIrLockedEntity = found;
            ywzj_rvp$lastIrLockTick = self.getVehicle().tickCount;
            return found;
        }
        return null;
    }

    @Unique
    private void rvp$clearIrLockMemory() {
        ywzj_rvp$lastIrLockedEntity = null;
        ywzj_rvp$lastIrLockTick = Integer.MIN_VALUE;
    }

    @OnlyIn(Dist.CLIENT)
    private static void rvp$restoreExternalRadarLock(WeaponUnit self, AbstractVehicleWeapon<?> rawWeapon) {
        WeaponUnit root = self.getRootParentWeaponUnit();
        if (root != self || self.getLockedEntity() != null) {
            return;
        }
        if (LocalVehiclePlayer.instance.vehicle != self.getVehicle()) {
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
        AbstractVehicleWeapon<?> resolved = RVP_LaserWeapons.unwrap(rawWeapon);
        if (resolved instanceof RVP_WeaponBase rvpWeapon
                && RVP_IrLockHelper.isIrLaunchWeapon(rvpWeapon.getData())) {
            Entity irTarget = RVP_IrLockHelper.resolveUsableIrTarget(self, null, externalLocked, rvpWeapon.getData());
            if (irTarget != null) {
                self.setLockedEntity(irTarget);
            }
            return;
        }
        if (externalLocked != null && externalLocked.isAlive()) {
            self.setLockedEntity(externalLocked);
        }
    }
}
