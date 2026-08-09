package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.AutoLandingGearManualOverrideManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;

import java.util.function.Supplier;

/**
 * 手动收放起落架覆盖标记（目标 {@link ClientVehicleAction}，不带毒类）。
 *
 * <p>原 {@code FixedWingVehicle} 版本的注入目标带毒（{@code onClientVehicleAction} 引用
 * {@code @OnlyIn(CLIENT)} 类型），无法在公共数组注册。改为拦截起落架切换消息
 * {@code ClientVehicleAction.toggleLandingGear}（与保留的
 * {@code ClientVehicleActionWeaponBayOverrideMixin} 同一安全模式），
 * 命中即标记该载具进入手动覆盖模式。</p>
 */
@Mixin(value = ClientVehicleAction.class, remap = false)
public abstract class AutoLandingGearManualOverrideMixin {

    @Inject(method = "onClientMessageReceived", at = @At("HEAD"), remap = false)
    private static void rvp$markManualOverride(ClientVehicleAction message,
                                               Supplier<NetworkEvent.Context> ctxSupplier,
                                               CallbackInfo ci) {
        if (!message.toggleLandingGear) {
            return;
        }
        NetworkEvent.Context context = ctxSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        Level level = player.level();
        Entity entity = level.getEntity(message.vehicleEntityId);
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!AutoLandingGearCache.isEnabled(vehicle.getVehicleId())) {
            return;
        }
        AutoLandingGearManualOverrideManager.markManualOverride(vehicle.getId());
    }
}
