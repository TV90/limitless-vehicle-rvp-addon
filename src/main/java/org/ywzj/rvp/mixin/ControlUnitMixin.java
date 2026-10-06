package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.uav.RVP_UavLoiterManager;
import org.ywzj.vehicle.network.message.ClientVehicleMoveControl;
import org.ywzj.vehicle.vehicle.control.ControlUnit;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * 盘旋激活时屏蔽玩家原生运动输入（鼠标瞄准/键盘运动），仅保留 functional 输入（武器操作）。
 * 制导逻辑由 {@link org.ywzj.rvp.uav.RVP_UavLoiterTickService} 写入 ControlUnit。
 */
@Mixin(ControlUnit.class)
public abstract class ControlUnitMixin {

    @Inject(
            method = "onClientMessageReceived",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void rvp$suppressMovementOnLoiter(ClientVehicleMoveControl message,
                                                     Supplier<NetworkEvent.Context> ctxSupplier,
                                                     CallbackInfo ci) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (ctx.getSender() == null) {
            return;
        }
        Level level = ctx.getSender().level();
        Entity entity = level.getEntity(message.vehicleEntityId);
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        // 守卫（2026-10-07 修复：此条件曾写反导致全载具运动包被 cancel）：非盘旋且非着舰/接管
        // → return 放行原方法（正常写入运动输入）；盘旋中 或 直升机着舰/接管流程中 → 落到下方
        // 屏蔽段（保留 functional 武器操作，吞运动输入）
        if (!RVP_UavLoiterManager.isLoitering(vehicle.getUUID())
                && !org.ywzj.rvp.helidock.RVP_HeliDockManager.isControlLocked(vehicle.getUUID())) {
            return;
        }
        // 盘旋时只保留 functional 输入（武器操作），屏蔽运动输入
        vehicle.controlUnit.functionalUp = message.functionalUp;
        vehicle.controlUnit.functionalDown = message.functionalDown;
        vehicle.controlUnit.functionalLeft = message.functionalLeft;
        vehicle.controlUnit.functionalRight = message.functionalRight;
        ctx.setPacketHandled(true);
        ci.cancel();
    }
}
