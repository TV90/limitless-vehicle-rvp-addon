package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.part.LandingGearUnit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 起落架自动收放：
 *   - 仅当 vehicle JSON 中 {@code rvp_auto_landing_gear: true} 时启用
 *   - 阈值可通过 JSON 配置（retract_speed / deploy_speed / deploy_height）
 *   - 玩家手动按 G 键后，5 秒内自动逻辑不干预（手动覆盖）
 */
@Mixin(value = Entity.class, remap = true)
public abstract class VehicleAutoLandingGearMixin {

    /** 手动覆盖冷却：5 秒 = 100 tick */
    private static final int MANUAL_OVERRIDE_TICKS = 100;

    /**
     * 实体 ID → 手动覆盖结束时的 game time。
     * 玩家手动按 G 键时写入，自动逻辑每 tick 检查是否过期。
     */
    private static final Map<Integer, Long> MANUAL_OVERRIDE_UNTIL = new ConcurrentHashMap<>();

    /** 供 {@link AutoLandingGearManualOverrideMixin} 调用：标记手动覆盖 */
    public static void markManualOverride(int entityId, long currentGameTime) {
        MANUAL_OVERRIDE_UNTIL.put(entityId, currentGameTime + MANUAL_OVERRIDE_TICKS);
    }

    /** 供实体移除时清理 */
    public static void removeOverride(int entityId) {
        MANUAL_OVERRIDE_UNTIL.remove(entityId);
    }

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
        Long overrideUntil = MANUAL_OVERRIDE_UNTIL.get(vehicle.getId());
        if (overrideUntil != null) {
            if (vehicle.level().getGameTime() < overrideUntil) {
                return;
            }
            MANUAL_OVERRIDE_UNTIL.remove(vehicle.getId());
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
