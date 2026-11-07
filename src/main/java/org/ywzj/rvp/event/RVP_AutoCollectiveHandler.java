package org.ywzj.rvp.event;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CRotaryFlightModeSync;
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
 * <p>原理：本体 Z 键每按一次翻转 public {@code hoverMode}（
 * {@code onClientVehicleAction} 直翻，不同步、客户端不可见）。程序切换经
 * {@link #setHoverMode(RotaryWingVehicle, boolean)} 同步模式；本处理器每 tick 检测
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

    /** 已接受的飞行模式；同时包含自动总距开关和 hover 快照，避免两张表不同步。 */
    private static final Map<UUID, RVP_RotaryFlightMode> FLIGHT_MODES = new ConcurrentHashMap<>();

    private RVP_AutoCollectiveHandler() {}

    /** 程序设置悬停时同步三态快照，避免被按键检测误转换成自动总距；不改变玩家的 Z 键循环。 */
    public static void setHoverMode(RotaryWingVehicle vehicle, boolean enabled) {
        vehicle.hoverMode = enabled;
        FLIGHT_MODES.put(vehicle.getUUID(), enabled ? RVP_RotaryFlightMode.HOVER : RVP_RotaryFlightMode.NORMAL);
        // 调用同步入口，让同车玩家的动作条立即切到悬停/常规图标
        syncModeToTracking(vehicle);
    }

    /** 把当前三态发给追踪该载具的客户端；无追踪者时为空操作。 */
    private static void syncModeToTracking(RotaryWingVehicle vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        RVP_RotaryFlightMode mode = FLIGHT_MODES.getOrDefault(vehicle.getUUID(),
                vehicle.hoverMode ? RVP_RotaryFlightMode.HOVER : RVP_RotaryFlightMode.NORMAL);
        RVP_Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> vehicle),
                new S2CRotaryFlightModeSync(vehicle.getId(), mode.ordinal()));
    }

    /** 玩家开始追踪载具时补发当前模式，覆盖中途上车/重连后的初始 HUD。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof RotaryWingVehicle vehicle)
                || vehicle.level().isClientSide()) {
            return;
        }
        sendModeToPlayer(player, vehicle);
    }

    /** 上车瞬间再补发一次，避免载具早已在视野内、状态又未变化时 HUD 初始值滞后。 */
    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || !event.isMounting()
                || !(event.getEntityMounting() instanceof ServerPlayer player)
                || !(event.getEntityBeingMounted() instanceof RotaryWingVehicle vehicle)) {
            return;
        }
        sendModeToPlayer(player, vehicle);
    }

    private static void sendModeToPlayer(ServerPlayer player, RotaryWingVehicle vehicle) {
        RVP_RotaryFlightMode mode = FLIGHT_MODES.getOrDefault(vehicle.getUUID(),
                vehicle.hoverMode ? RVP_RotaryFlightMode.HOVER : RVP_RotaryFlightMode.NORMAL);
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CRotaryFlightModeSync(vehicle.getId(), mode.ordinal()));
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
        // --- Z 键三态检测：本体翻转 hoverMode = 用户按键信号，按三态推进/校正 ---
        // 资格前置（2026-10-13 玩家反馈"切不了悬停"后修正）：非 RVP 直升机（无 rvp 扩展配置）
        // 不参与三态机——若放行进 toggle，wasAuto 恒 false 会把 hoverMode 强制改回 false
        // （拦截悬停改去自动总距态），随后兜底又打回常规，玩家按 Z 永远进不了悬停。
        // 此处直接放行：非 RVP 直升机的 Z 键保持本体原版悬停开/关（纯二态）。
        if (!RVP_VehicleExtendedConfigManager.hasAutoCollectiveConfig(vehicle.getVehicleId())) {
            return;
        }
        UUID uuid = vehicle.getUUID();
        RVP_RotaryFlightMode mode = FLIGHT_MODES.computeIfAbsent(uuid,
                ignored -> vehicle.hoverMode ? RVP_RotaryFlightMode.HOVER : RVP_RotaryFlightMode.NORMAL);
        // 调用同一三态转换规则；setHoverMode 已登记的程序切换在这里保持不变。
        RVP_RotaryFlightMode next = mode.observeHover(vehicle.hoverMode);
        if (next != mode) {
            FLIGHT_MODES.put(uuid, next);
            vehicle.hoverMode = next == RVP_RotaryFlightMode.HOVER;
            // 调用同步入口，把 Z 键切换后的三态推给同车玩家
            syncModeToTracking(vehicle);
            // 调用现有提示入口，保留玩家 Z 键循环的三种反馈。
            notifyMode(vehicle, switch (next) {
                case NORMAL -> "rvp.flight_mode.normal";
                case AUTO_COLLECTIVE -> "rvp.flight_mode.auto_collective";
                case HOVER -> "rvp.flight_mode.hover";
            });
            return; // 切换 tick 不执行阻尼，下 tick 按新态工作
        }
        if (mode != RVP_RotaryFlightMode.AUTO_COLLECTIVE) {
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
            // 未配置 rvp_auto_collective 参数块 = 该载具不支持自动总距（开关默认关闭语义）。
            // 兜底（2026-10-12 玩家崩服后加）：非 RVP 直升机没有 rvp 扩展配置，若此前被
            // Z 键切进了自动总距态，此处静默回常规态，避免
            // 玩家停在"切了但永远无效果"的哑态（崩溃本体已由 Manager 判空修复）。
            FLIGHT_MODES.put(uuid, RVP_RotaryFlightMode.NORMAL);
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
        if (entity instanceof AbstractVehicle && !entity.level().isClientSide()) {
            FLIGHT_MODES.remove(entity.getUUID());
        }
    }

    /** 单人游戏关服后也清空状态，避免下一次载入沿用旧的按键快照。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FLIGHT_MODES.clear();
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
