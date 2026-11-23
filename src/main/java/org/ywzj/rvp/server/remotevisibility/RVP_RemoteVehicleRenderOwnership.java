package org.ywzj.rvp.server.remotevisibility;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.config.RVP_CommonConfig.VisibilityMode;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * RVP 与本体远距载具视觉的所有权协调器。
 *
 * <p>RVP 启用完整远距视觉时，关闭本体 {@code renderBeyondRenderDistance} 的视觉出口，
 * 但不关闭本体 {@code ServerBroadcastEntities} 的雷达实体分支。RVP 关闭后恢复每辆载具
 * 原本的值，避免配置切换导致本体功能永久失效。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteVehicleRenderOwnership {
    /** 载具原始的本体远距视觉开关；弱引用避免实体离开世界后长期占用内存。 */
    private static final Map<AbstractVehicle, Boolean> ORIGINAL_BASE_RENDER_FLAGS = new WeakHashMap<>();

    /** 工具类不允许实例化。 */
    private RVP_RemoteVehicleRenderOwnership() {
    }

    /**
     * 载具加入服务端世界时立即收敛所有权，避免等待首个同步周期才关闭本体视觉出口。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        reconcile(vehicle, ownsRemoteVehicleVisuals());
    }

    /**
     * 在服务端广播实体前收敛所有载具的本体远距视觉开关，确保同一 tick 不产生双重模型。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        boolean ownsRemoteVisuals = ownsRemoteVehicleVisuals();
        if (!ownsRemoteVisuals) {
            restoreOriginalFlags();
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    reconcile(vehicle, true);
                }
            }
        }
    }

    /** 服务器停止时恢复残留实体的本体开关，并清除当前服务器的所有权状态。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        restoreOriginalFlags();
    }

    /**
     * 同步单辆 RVP 可分类载具的本体视觉开关。
     *
     * @param vehicle 当前服务端载具
     * @param ownsRemoteVisuals true=由 RVP 独占远距视觉，false=恢复本体视觉
     */
    private static void reconcile(AbstractVehicle vehicle, boolean ownsRemoteVisuals) {
        // 调用 RVP 载具分类策略，只接管 RVP 远距视觉实际支持的完整载具。
        if (RVP_RemoteVehicleVisibilityPolicy.classify(vehicle).isEmpty()) {
            return;
        }
        if (!ownsRemoteVisuals) {
            Boolean original = ORIGINAL_BASE_RENDER_FLAGS.remove(vehicle);
            if (original != null) {
                vehicle.renderBeyondRenderDistance = original;
            }
            return;
        }
        ORIGINAL_BASE_RENDER_FLAGS.putIfAbsent(vehicle, vehicle.renderBeyondRenderDistance);
        vehicle.renderBeyondRenderDistance = false;
    }

    /** 判断 RVP 当前是否应独占可分类载具的远距视觉。 */
    private static boolean ownsRemoteVehicleVisuals() {
        // 调用 RVP common 配置读取总开关，避免 RVP 关闭后误屏蔽本体视觉。
        return RVP_CommonConfig.isRemoteVehicleRenderingEnabled()
                // 调用 RVP common 配置读取可见性模式，OFF 模式下交还本体视觉所有权。
                && RVP_CommonConfig.getRemoteVehicleVisibilityMode() != VisibilityMode.OFF;
    }

    /** 恢复所有已接管载具，供配置关闭和服务端停止共用。 */
    private static void restoreOriginalFlags() {
        for (Map.Entry<AbstractVehicle, Boolean> entry : ORIGINAL_BASE_RENDER_FLAGS.entrySet()) {
            AbstractVehicle vehicle = entry.getKey();
            if (vehicle != null) {
                vehicle.renderBeyondRenderDistance = entry.getValue();
            }
        }
        ORIGINAL_BASE_RENDER_FLAGS.clear();
    }
}
