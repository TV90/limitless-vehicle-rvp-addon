package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.ManualOverrideManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.part.LandingGearUnit;

/**
 * 起落架自动收放：
 *   - 仅当 vehicle JSON 中 {@code rvp_auto_landing_gear: true} 时启用
 *   - 阈值可通过 JSON 配置（retract_speed / deploy_speed / deploy_height）
 *   - 玩家手动按 G 键后，5 秒内自动逻辑不干预（手动覆盖）
 *   - 手动覆盖状态由 {@link ManualOverrideManager} 统一管理
 */
@Mixin(value = Entity.class, remap = true)
public abstract class VehicleAutoLandingGearMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void rvp$onEntityTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractVehicle vehicle)) return;
        if (vehicle.level().isClientSide()) return;

        AutoLandingGearCache.AutoLandingGearConfig config = AutoLandingGearCache.get(vehicle.getVehicleId());
        if (!config.enabled) return;

        LandingGearUnit gear = null;
        if (vehicle instanceof FixedWingVehicle fw) {
            gear = fw.landingGear;
        } else if (vehicle instanceof RotaryWingVehicle rw) {
            gear = rw.landingGear;
        }
        if (gear == null) return;

        // 手动覆盖冷却期内，不执行自动逻辑
        if (ManualOverrideManager.isOverrideActive(vehicle.getId(), vehicle.level().getGameTime())) {
            return;
        }

        // m/s → km/h
        double speed = vehicle.getDeltaMovement().length() * 20.0 * 3.6;
        // 离地高度
        double groundHeight = vehicle.getY() - vehicle.level().getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                vehicle.blockPosition()).getY();

        if (speed > config.retractSpeed && !gear.isOn()) {
            gear.setOn(true);  // 收起
        } else if (speed < config.deploySpeed && groundHeight < config.deployHeight && gear.isOn()) {
            gear.setOn(false); // 放下
        }
    }
}
