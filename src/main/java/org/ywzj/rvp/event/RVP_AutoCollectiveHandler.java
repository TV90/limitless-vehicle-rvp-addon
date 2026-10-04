package org.ywzj.rvp.event;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.C2SToggleFlightMode;
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 直升机自动总距模式（2026-10-05，用户需求）：三态飞行模式（常规 → 自动总距 → 悬停）
 * 的服务端状态与执行器。零 Mixin——写入路径全部走本体公共 API：
 *
 * <ul>
 *   <li><b>三态编码</b>：{@code hoverMode}（本体 public 字段，本类可写）+ {@link #AUTO_COLLECTIVE}
 *       侧表两个正交布尔组合：常规 = hover+auto 双 false；自动总距 = hover=false + auto=true；
 *       悬停 = hover=true（auto 强制 false）。循环键每次切换前实时读 hoverMode 校准，
 *       本体 Z 键的直接操作被下一次循环正确吸收；</li>
 *   <li><b>执行闭环</b>：{@link VehicleMoveEvent} 在每 tick 物理（含 tickMove）之后发布，
 *       此处调本体 public {@code setCollectivePitch()}（写 SynchedEntityData）成为下一 tick
 *       {@code tickMove} 的总距读取基准——与 {@code RVP_EnginePowerHandler} 同款已验证模式。</li>
 * </ul>
 *
 * <p><b>自动总距语义（区别于悬停模式）</b>：只做垂直速度通道的阻尼，姿态/航向完全不受限，
 * 保留常规模式全部机动性——爬升率过高自动调低总距、下坠率过高自动调高总距，
 * 确保短时间内高度无巨大变化。vy 判定与本体悬停阻尼同源
 * （{@code vehicle.airSpeed.y - PhysicsEngine.G}，airSpeed 为 public 字段且在事件时刻
 * 精确持有 tickMove 推力结算后的值）。悬停开着时跳过（本体悬停自带 0.01 敏感阻尼，
 * 重复介入无意义）；玩家按着 SPACE/SHIFT（controlUnit.up/down）时本 tick 让位——手动优先。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_AutoCollectiveHandler {

    /** 自动总距模式侧表：载具 UUID → 是否启用。仅服务端读写；车辆卸载即清理。 */
    private static final Map<UUID, Boolean> AUTO_COLLECTIVE = new ConcurrentHashMap<>();

    private RVP_AutoCollectiveHandler() {}

    /** 三态循环切换入口（{@link C2SToggleFlightMode} 服务端校验通过后调用）。 */
    public static void toggleFlightMode(RotaryWingVehicle vehicle) {
        boolean autoOn = AUTO_COLLECTIVE.getOrDefault(vehicle.getUUID(), false);
        // 本体 Z 键可能直接翻过 hoverMode，切换前以实时值为准；auto 与 hover 互斥（悬停态强制 auto=false）
        boolean hoverOn = vehicle.hoverMode;
        if (hoverOn) {
            // 悬停 → 常规
            vehicle.hoverMode = false;
            AUTO_COLLECTIVE.put(vehicle.getUUID(), false);
            notifyMode(vehicle, "rvp.flight_mode.normal");
        } else if (autoOn) {
            // 自动总距 → 悬停（写本体 public 字段，行为与本体内 Z 键翻转完全一致）
            AUTO_COLLECTIVE.put(vehicle.getUUID(), false);
            vehicle.hoverMode = true;
            notifyMode(vehicle, "rvp.flight_mode.hover");
        } else {
            // 常规 → 自动总距
            AUTO_COLLECTIVE.put(vehicle.getUUID(), true);
            notifyMode(vehicle, "rvp.flight_mode.auto_collective");
        }
    }

    /**
     * 每 tick 执行：垂直速度阻尼式自动总距。镜像本体 hoverMode 阻尼段
     * （RotaryWingVehicle.tickMove 的 vy = airSpeed.y - G、每 tick 总距 ±1），
     * 但阈值/速率由载具 JSON {@code rvp_auto_collective} 可配，且不锁姿态、玩家输入让位。
     */
    @SubscribeEvent
    public static void onVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof RotaryWingVehicle vehicle)
                || vehicle.level().isClientSide()
                || vehicle.isDestroyed()
                || vehicle.isRemoved()) {
            return;
        }
        if (!AUTO_COLLECTIVE.getOrDefault(vehicle.getUUID(), false)
                || vehicle.hoverMode) {
            // 未启用；或悬停态（本体自带更敏感的阻尼，重复介入无意义）
            return;
        }
        // 玩家正在手动打总距：本 tick 让位（手动优先，松开自动接管）
        if (vehicle.controlUnit.up || vehicle.controlUnit.down) {
            return;
        }
        ResourceLocation vehicleId = vehicle.getVehicleId();
        RVP_VehicleExtendedConfigManager.VehicleExtendedConfig.AutoCollectiveConfig config =
                RVP_VehicleExtendedConfigManager.getAutoCollectiveConfig(vehicleId);
        if (config == null || !config.enabled()) {
            // 未配置 rvp_auto_collective 参数块 = 该载具不支持自动总距（开关默认关闭语义）
            return;
        }
        // 与本体 hoverMode 阻尼同源的垂直速度基准（推力结算后、物理积分前）
        double vy = vehicle.airSpeed.y - PhysicsEngine.G;
        float rate = config.ratePerTick();
        if (vy > config.thresholdClimb()) {
            // 爬升率过高 → 自动调低总距
            vehicle.setCollectivePitch(Mth.clamp(vehicle.getCollectivePitch() - rate, 0f, 100f));
        } else if (vy < -config.thresholdDescent()) {
            // 下坠率过高 → 自动调高总距
            vehicle.setCollectivePitch(Mth.clamp(vehicle.getCollectivePitch() + rate, 0f, 100f));
        }
    }

    /** 车辆离开维度（卸载/销毁）即清理侧表，防泄漏。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof AbstractVehicle) {
            AUTO_COLLECTIVE.remove(entity.getUUID());
        }
    }

    /** 向载具驾驶员发三态切换提示（actionbar，translatable 双端安全）。 */
    private static void notifyMode(RotaryWingVehicle vehicle, String key) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable(key), true);
            }
        }
    }
}
