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
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.Optional;

/**
 * Lets RVP homing weapons participate in the base auto-lock flow and adds a small IR hysteresis band
 * so edge-of-FOV locks do not chatter between lose/reacquire every few ticks.
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitTickFireControlMixin {

    // [RVP] 2026-10-21：原 6 tick 重锁宽限与 lastIr 记忆字段删除——IR 锁定改由
    // RVP_IrSeekerTracker 连续 dwell 状态机接管（hold 保活读 seeker_lock_hold_grace_tick）。

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
            return;
        }

        if (weaponOpt.isEmpty()) {
            return;
        }
        AbstractVehicleWeapon<?> resolved = RVP_LaserWeapons.unwrap(weaponOpt.get());
        if (!(resolved instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        if (!rvpWeapon.getData().isHomingProjectile()) {
            return;
        }
        if (rvpWeapon.getData().isAntiRadiationMissile()) {
            if (self.getLockedEntity() != null) {
                self.setLockedEntity(null);
            }
            return;
        }

        WeaponUnitData.FireControlSensorType sensorType = self.getFireControlSensorType();
        if (sensorType == WeaponUnitData.FireControlSensorType.RF) {
            // [RVP] 2026-10-21：20t 门控与"已有锁早退"自公共路径【移进 RF 分支】（非删除）——
            // ARH 雷达弹的 TWS 门控手感保持原值不变（方案 §5.3 / 红线 1）。
            if (self.getLockCoolingTick() <= 20) {
                return;
            }
            if (self.getLockedEntity() != null) {
                return;
            }
            // 雷达自动锁定（TWS 扫描跟踪）：ARH 等通过雷达 TWS 数据链制导，保持自动锁定
            // （模拟现实中雷达边扫描边跟踪给主动弹提供数据链目标更新）；
            // SARH 半主动雷达弹需要载具【锁定】目标才制导，扫描阶段不自动锁定，
            // 避免"只开导引头、雷达照射到目标"时 SARH 未手动锁定也能获得制导。
            if (!rvpWeapon.getData().usesGuidanceType(RVP_EnumGuidanceType.SARH)) {
                RadarUnit radar = RVP_RadarRoleHelper.getPreferredLockRadar(self);
                if (radar != null) {
                    Entity entity = Radar.findTarget(radar, 90, self);
                    // IR 发射武器的 RF 供锁补离轴校验（2026-09-20 离轴角 bug 修复，bug B 治本）：
                    // 雷达 TWS 锥（90°）远大于 IR 导引头离轴角，直接落锁会让 IR 弹获得超锥锁
                    //（发射授权的离轴终检会兜底拒射，这里在锁建立时就拦掉，语义更干净）。
                    // ARH 等雷达制导弹不受 IR 离轴角约束（isIrLaunchWeapon 已排除雷达/反辐射/GPS）。
                    if (entity != null && !RVP_IrLockHelper.isLaunchTargetWithinOffAxis(
                            self, entity, rvpWeapon.getData(), 0f)) {
                        entity = null;
                    }
                    if (entity != null) {
                        self.setLockedEntity(entity);
                    }
                }
            }
            return;
        }

        // IR / EO 代捕获分支：只喂 RVP_IrSeekerTracker（连续 dwell 状态机），不再直接落锁。
        // 站级 lockedEntity（EO/RF/HMD 写入的）对本弹只是候选提示，绝不直接给 IR 授权。
        if (sensorType == WeaponUnitData.FireControlSensorType.IR
                || RVP_IrLockHelper.usesIrAcquireOnEo(sensorType, rvpWeapon.getData())) {
            org.ywzj.rvp.client.state.RVP_IrSeekerTracker.tickFor(self, rvpWeapon);
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

    // [RVP] 2026-10-21：rvp$resolveIrLockWithHysteresis / rvp$clearIrLockMemory 删除——
    // 候选扫描与 dwell 移入 RVP_IrSeekerTracker.tickFor（client/state）。

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
