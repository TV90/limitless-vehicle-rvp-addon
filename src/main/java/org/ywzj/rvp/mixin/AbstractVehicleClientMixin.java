package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;

/**
 * 本体载具的客户端行为接管点：
 * <ul>
 *   <li>整车击毁后取消本体持续冒烟，保留水面气泡；RVP 烟团由独立客户端事件发射。</li>
 *   <li>载具间切换时保护本地座位状态，等待目标载具的座位同步包更新。</li>
 * </ul>
 * 仅列入 mixin 配置的 client 数组，服务端不加载这些客户端行为。
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleClientMixin {

    /**
     * 已击毁整车进入本体 tickParticle 时，取消其烟云和残骸烟。
     * 脱落残件 VehiclePart 独立覆写该方法且不调用 super，不受此注入影响；
     * 子类在 super.tickParticle() 之后的其他粒子逻辑仍会执行。
     */
    @Inject(method = "tickParticle", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$replaceDestroyedVehicleSmoke(CallbackInfo ci) {
        AbstractVehicle vehicle = (AbstractVehicle) (Object) this;
        if (!vehicle.isDestroyed()) {
            return;
        }
        // 调用本项目双端安全桥：保留本体此方法原有的水面气泡，不直接引用客户端粒子类型。
        RVP_ClientActionsAccess.preserveDestroyedVehicleWaterBubbles(vehicle);
        ci.cancel();
    }

    /**
     * 客户端从一辆载具转移到另一辆载具时，阻止旧载具把 LocalVehiclePlayer 的座位清空。
     * 本体 onLeaveVehicle 会在 toLeave 为 true 时调用 toSeat(null, this)；目标载具接管后，
     * 由 ServerVehicleSeatsChange 完成新座位状态同步。
     */
    @Inject(method = "onLeaveVehicle", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$keepClientStateOnVehicleTransfer(LivingEntity passenger, CallbackInfo ci) {
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
