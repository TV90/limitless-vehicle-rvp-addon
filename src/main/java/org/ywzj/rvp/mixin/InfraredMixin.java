package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
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
    private static void ywzj_rvp$overrideCheckTarget(WeaponUnit weaponUnit, Entity target, CallbackInfoReturnable<Entity> cir) {
        // 只在 IR HMD 激活时介入
        RVP_ClientHmdState state = RVP_ClientHmdState.getInstance();
        if (!state.isIrHmd()) {
            return;
        }

        // 从 HMD 状态获取当前锁定实体及离轴角
        Entity hmdLocked = weaponUnit.getLockedEntity();
        if (hmdLocked == null || hmdLocked.getId() != target.getId()) {
            return; // HMD 锁定的是其他目标，不干预
        }

        // 获取 HMD 的离轴角限制和高度过滤
        float maxAngle = state.getIrGuideHeadMaxAngle();
        float lockMinHeight = state.getIrLockMinHeight();

        // 离地高度过滤
        if (!RVP_GuidanceMath.isTargetPassAltFilter(target, lockMinHeight)) {
            cir.setReturnValue(null);
            return;
        }

        if (maxAngle <= 0) {
            return;
        }

        // 计算目标相对武器指向的离轴角
        Vec3 checkStart = weaponUnit.worldPivotPosition();
        Vec3 vLock = target.getBoundingBox().getCenter().subtract(checkStart);
        Vec3 vAim = weaponUnit.worldVec();
        double degree = Math.toDegrees(VectorUtil.angleBetween(vLock, vAim));

        if (degree > maxAngle) {
            // 超出离轴角 → 丢锁
            cir.setReturnValue(null);
        } else {
            // 在离轴角内 → 维持锁定（覆盖原 Infrared 的判定）
            cir.setReturnValue(target);
        }
    }
}
