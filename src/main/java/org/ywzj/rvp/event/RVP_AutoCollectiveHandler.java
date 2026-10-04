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
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 直升机三态飞行模式（2026-10-05，用户需求）：常规 → 自动总距 → 悬停 → 常规，
 * **由本体 Z 键（悬停切换）直接驱动，零 Mixin、零新增包**。
 *
 * <p>原理：本体 Z 键每按一次翻转 public {@code hoverMode}（唯一入口，
 * {@code onClientVehicleAction} 直翻，不同步、客户端不可见）。本处理器每 tick 检测
 * {@code hoverMode} 变化并按三态校正：</p>
 * <ul>
 *   <li>常规(F,F) 按 Z → 本体翻为 T → 检测到 F→T 且此前非自动总距 → <b>校正回 F 并开启
 *       自动总距</b>（进入自动总距态而非悬停）；</li>
 *   <li>自动总距(F,T) 按 Z → 本体翻为 T → auto 已开 → 维持 T = 进入悬停态（auto 关闭）；</li>
 *   <li>悬停(T,F) 按 Z → 本体翻为 F → 回到常规态。</li>
 * </ul>
 *
 * <p><b>自动总距语义</b>：只做垂直速度通道阻尼（爬升率过高自动调低总距、下坠率过高自动
 * 调高总距），姿态/航向完全不受限——区别于悬停模式的回平+锁航向。vy 与本体悬停阻尼同源
 * （{@code vehicle.airSpeed.y - PhysicsEngine.G}，事件时刻精确持有推力结算后的值）；写入走
 * 本体 public {@code setCollectivePitch()}（VehicleMoveEvent 后发布，下 tick 生效，
 * {@code RVP_EnginePowerHandler} 同款已验证模式）。悬停开着时跳过（本体悬停自带 0.01
 * 敏感阻尼）；玩家按着 SPACE/SHIFT 时让位（手动优先）。阈值/速率由载具 JSON
 * {@code rvp_auto_collective} 可配。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_AutoCollectiveHandler {

    /** 自动总距模式侧表：载具 UUID → 是否启用。仅服务端读写；车辆卸载即清理。 */
    private static final Map<UUID, Boolean> AUTO_COLLECTIVE = new ConcurrentHashMap<>();
    /** hoverMode 上一 tick 快照：检测本体 Z 键翻转（变化 = 用户按键，推进三态）。 */
    private static final Map<UUID, Boolean> LAST_HOVER = new ConcurrentHashMap<>();

    private RVP_AutoCollectiveHandler() {}

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
        // --- Z 键三态检测：本体翻转 hoverMode = 用户按键信号，按三态推进/校正 ---
        UUID uuid = vehicle.getUUID();
        boolean hover = vehicle.hoverMode;
        Boolean lastHover = LAST_HOVER.put(uuid, hover);
        if (lastHover != null && hover != lastHover) {
            if (!lastHover && hover) {
                // F→T：此前常规（auto=false）→ 校正为自动总距态；此前自动总距 → 维持悬停
                boolean wasAuto = AUTO_COLLECTIVE.getOrDefault(uuid, false);
                if (!wasAuto) {
                    vehicle.hoverMode = false;
                    // 快照必须对齐校正后的值：否则下 tick 读到校正后的 F 与快照 T 比对，
                    // 误判为"又变化了一次（T→F）"走退出分支——表现为进自动总距瞬间回常规
                    LAST_HOVER.put(uuid, false);
                    AUTO_COLLECTIVE.put(uuid, true);
                    notifyMode(vehicle, "rvp.flight_mode.auto_collective");
                } else {
                    AUTO_COLLECTIVE.put(uuid, false);
                    notifyMode(vehicle, "rvp.flight_mode.hover");
                }
            } else {
                // T→F：悬停退出 → 常规态
                AUTO_COLLECTIVE.put(uuid, false);
                notifyMode(vehicle, "rvp.flight_mode.normal");
            }
            return; // 切换 tick 不执行阻尼，下 tick 按新态工作
        }
        if (!AUTO_COLLECTIVE.getOrDefault(uuid, false)
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
