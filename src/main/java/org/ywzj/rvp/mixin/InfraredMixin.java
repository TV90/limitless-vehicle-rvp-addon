package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
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
            if (!RVP_IrLockHelper.isTargetWithinLimits(
                    weaponUnit,
                    target,
                    RVP_IrLockHelper.resolveIrBoresightDir(weaponUnit),
                    Math.max(1f, state.getIrGuideHeadMaxAngle()),
                    Math.max(0f, state.getIrSeekerRange()),
                    state.getIrLockMinHeight()
            )) {
                cir.setReturnValue(null);
            }
            return;
        }

        var weaponOpt = weaponUnit.getCurrentWeapon();
        if (weaponOpt.isEmpty() || !(weaponOpt.get() instanceof RVP_WeaponBase rvpWeapon)) {
            return;
        }
        var data = rvpWeapon.getData();
        if (!RVP_IrLockHelper.isIrLaunchWeapon(data)) {
            return;
        }
        if (!RVP_IrLockHelper.isTargetWithinHoldLimits(weaponUnit, target, data)) {
            cir.setReturnValue(null);
        }
    }
}
