package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.server.wreck.RVP_DestroyedPartLaunchManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VehiclePartSpawner;

/**
 * 最小击毁飞头拦截；静态工具目标不含客户端专用类型，公共数组可安全加载。
 * 本体没有“扣血/击毁后、脱落前”事件，JSON/继承/访问器均不能延后静态调用。
 * 业务和共享状态全部放在独立管理器中，不改 AbstractVehicle 的字节码。
 */
@Mixin(value = VehiclePartSpawner.class, remap = false)
public abstract class VehiclePartSpawnerMixin {
    /** 转发一次决定；仅管理器要求延期或禁止飞头时取消本体生成。 */
    @Inject(method = "spawnDestroyedParts", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rvp$scheduleDestroyedParts(AbstractVehicle vehicle, CallbackInfo ci) {
        // 调用本项目服务端管理器，立即飞头及非地面载具原样进入本体代码。
        if (RVP_DestroyedPartLaunchManager.interceptNativeSpawn(vehicle)) ci.cancel();
    }
}
