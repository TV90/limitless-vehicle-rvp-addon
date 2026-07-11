package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

/**
 * IR HMD 锁定期间，用离轴角（guide_head_max_angle）代替 seeker FOV 做锁定维持判定。
 */
@Mixin(value = Infrared.class, remap = false)
public class InfraredMixin {

    @Inject(
            method = "checkTarget",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void ywzj_rvp(WeaponUnit weaponUnit, Entity target, CallbackInfoReturnable<Entity> cir) {
        if (cir.getReturnValue() == null || target == null) {
            return;
        }

        RVP_ClientHmdState state = RVP_ClientHmdState.getInstance();
        if (state.isIrHmd()) {
            Entity hmdLocked = weaponUnit.getLockedEntity();
            if (hmdLocked == null || hmdLocked.getId() != target.getId()) {
                return;
            }
            float maxAngle = state.getIrGuideHeadMaxAngle();
            float lockMinHeight = state.getIrLockMinHeight();
            if (!RVP_GuidanceMath.isTargetPassAltFilter(target, lockMinHeight)) {
                cir.setReturnValue(null);
                return;
            }
            ywzj_rvp(weaponUnit, target, maxAngle, cir);
            return;
        }

        var weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isEmpty() || !(weaponOpt.get() instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        var data = rvpWeapon.getData();
        if (!data.isHomingProjectile() || !data.usesGuidanceType(RVP_EnumGuidanceType.IR)) {
            return;
        }
        ywzj_rvp(weaponUnit, target, data.getMaxGuideHeadAngle(), cir);
    }

    private static void ywzj_rvp(WeaponUnit weaponUnit, Entity target, float maxAngle,
                                                 CallbackInfoReturnable<Entity> cir) {
        if (maxAngle <= 0f) {
            return;
        }
        Vec3 checkStart = weaponUnit.worldPivotPosition();
        Vec3 vLock = target.getBoundingBox().getCenter().subtract(checkStart);
        Vec3 vAim = weaponUnit.worldVec();
        double degree = Math.toDegrees(VectorUtil.angleBetween(vLock, vAim));
        if (degree > maxAngle) {
            cir.setReturnValue(null);
        } else {
            cir.setReturnValue(target);
        }
    }
}
