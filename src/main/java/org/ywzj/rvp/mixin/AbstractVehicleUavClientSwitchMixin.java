package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 无人机视角切换（M 键）由服务端直接 startRiding 完成，绕过了本体客户端的座位状态机：
 * 母车 removePassenger → onLeaveVehicle（客户端）→ instance.toSeat(null, this)
 * 会把 LocalVehiclePlayer.vehicle/seat 清空，导致 onVehicle()==false，
 * 进而 V 键切视角、武器发射（InputHandler/ShootKeyMixin）以及高度速度等 HUD
 * （VehicleOverlay/VehicleWeaponOverlay 等）全部失效。
 *
 * 这里在客户端拦截：若玩家离开本车后已（或即将）骑上另一辆 AbstractVehicle
 * （无人机 ↔ 母车切换），则跳过状态清空，等待目标车辆的 ServerVehicleSeatsChange
 * 重新绑定 instance 状态。
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleUavClientSwitchMixin {

    @Inject(method = "onLeaveVehicle", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$keepClientStateOnUavSwitch(LivingEntity passenger, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!self.level().isClientSide()) {
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (passenger != instance.getPlayer() || !instance.toLeave) {
            return;
        }
        // 目标车辆已经接管（instance.vehicle 已指向另一辆车），
        // 或玩家已骑上另一辆 AbstractVehicle 时，不允许清空客户端座位状态。
        boolean takingOver = (instance.vehicle != null && instance.vehicle != self)
                || (passenger.getVehicle() instanceof AbstractVehicle other && other != self);
        if (takingOver) {
            ci.cancel();
        }
    }
}
