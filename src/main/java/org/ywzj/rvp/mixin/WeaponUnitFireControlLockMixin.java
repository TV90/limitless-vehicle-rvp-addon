package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

import java.util.Optional;

/**
 * 按 R 手锁时自动开启导引头（seekerOn），方便 HUD 反馈。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitFireControlLockMixin {
    @OnlyIn(Dist.CLIENT)
    @Inject(method = "fireControlLock", at = @At("HEAD"), cancellable = true, remap = false)
    private void rvp$handleRfFireControlLock(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        Optional<?> weaponOpt = self.getCurrentWeapon();
        AbstractVehicleWeapon<?> currentWeapon = weaponOpt.isPresent() && weaponOpt.get() instanceof AbstractVehicleWeapon<?> weapon
                ? weapon
                : null;
        // 动态传感器判断（eo_ccip/override），恢复传感器覆盖 mixin 被删前的行为
        WeaponUnitData.FireControlSensorType sensorType = RVP_WeaponSensorHelper.effectiveSensorType(self);
        if (sensorType == WeaponUnitData.FireControlSensorType.EO
                && weaponOpt.isPresent()
                && RVP_LaserWeapons.unwrap(currentWeapon) instanceof RVP_WeaponBase rvpWeapon
                && RVP_IrLockHelper.usesIrAcquireOnEo(sensorType, rvpWeapon.getData())) {
            if (self.getLockedEntity() != null) {
                self.setLockedEntity(null);
                ci.cancel();
                return;
            }
            Entity target = Infrared.findTarget(self, RVP_IrLockHelper.halfAngleFromFull(rvpWeapon.getData().resolveLaunchSeekerFullFov()));
            if (!RVP_IrLockHelper.isTargetWithinAcquireLimits(self, target, rvpWeapon.getData())) {
                target = null;
            }
            if (target != null) {
                self.setLockedEntity(target);
                if (!self.isSeekerOn()) {
                    self.toggleSeeker(true);
                }
            }
            ci.cancel();
            return;
        }
        if (sensorType != WeaponUnitData.FireControlSensorType.RF) {
            return;
        }

        Entity radarLocked = RVP_RadarRoleHelper.getLockedRadarEntity(self);
        if (radarLocked != null) {
            RVP_RadarRoleHelper.clearAllRadarLocks(self);
            self.setLockedEntity(null);
            ci.cancel();
            return;
        }
        if (RVP_ExternalRadarLinkHelper.hasClientExternalLockState(LocalVehiclePlayer.instance.getVehicle(),
                net.minecraft.client.Minecraft.getInstance().level != null
                        ? net.minecraft.client.Minecraft.getInstance().level.dimension().location()
                        : null)) {
            RVP_ExternalRadarLinkHelper.clearClientLockRequest(self);
            ci.cancel();
            return;
        }
        if (self.getLockedEntity() != null) {
            RVP_RadarRoleHelper.clearPendingRadarLock(self);
            self.setLockedEntity(null);
            ci.cancel();
            return;
        }

        Entity target = RVP_RadarRoleHelper.findManualLockCandidate(self);
        if (target != null && RVP_RadarRoleHelper.applyRequestedLock(self, target)) {
            ci.cancel();
            return;
        }
        RVP_ExternalRadarLinkHelper.ClientLockCandidate externalCandidate =
                RVP_ExternalRadarLinkHelper.findViewManualClientLockCandidateData(self);
        if (externalCandidate != null
                && RVP_ExternalRadarLinkHelper.applyClientLockRequest(self, externalCandidate.entityId())) {
            ci.cancel();
            return;
        }

        if (self.withFocusLocker() && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
            if (self.getFocusLockPos() == null) {
                self.setFocusLockPos(LocalVehiclePlayer.instance.scopeAimPos());
            } else {
                self.setFocusLockPos(null);
            }
        } else if (self.getFocusLockPos() != null) {
            self.setFocusLockPos(null);
        }
        ci.cancel();
    }


    @OnlyIn(Dist.CLIENT)
    @Inject(method = "fireControlLock", at = @At("TAIL"), remap = false)
    private void rvp$onFireControlLock(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;

        // 检查是否锁上了目标
        boolean locked = false;
        RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(self);
        if (radar != null && radar.getLockedEntity() != null) {
            locked = true;
        }
        if (!locked && self.getLockedEntity() != null) {
            locked = true;
        }

        // 只对 RVP 武器生效
        RVP_WeaponBase rvpWeapon = RVP_WeaponResolveHelper.currentPrimaryRvp(self);
        if (rvpWeapon == null) return;

        // ARM 反辐射导弹：即使无锁也开启导引头，用于预选扫描
        if (rvpWeapon.getData().isAntiRadiationMissile()) {
            if (!self.isSeekerOn()) {
                self.toggleSeeker(true);
            }
            return;
        }

        // 其他 RVP 导弹：需要锁上目标后才开启导引头
        if (!locked) return;
        if (!self.isSeekerOn()) {
            self.toggleSeeker(true);
        }
    }
}
