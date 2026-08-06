package org.ywzj.rvp.event;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * physics-only 碰撞体积的驱动：
 * <ul>
 *   <li>载具加入世界 → 清除重建标志；真正重建在每 tick（服务端/客户端各一）遍历时执行
 *       （替代被删 mixin 的 {@code initData} 注入——EntityJoinLevelEvent 早于 initData，
 *       此时 centerOffset 为 null、bodyCubes 为空，立即重建会在 updatePhysicsOnlyCubes 处 NPE，
 *       导致带 physics_only_bone 的载具“放不出来”）；</li>
 *   <li>每 tick（服务端/客户端各一）→ 更新盒的世界坐标（替代 {@code updateOBBs} 注入）；</li>
 *   <li>载具离开世界 → 清理侧表条目与重建标志。</li>
 * </ul>
 *
 * <p>Forge 1.20.1 无 {@code TickEvent.EntityTickEvent}，故在 Server/ClientTick 里遍历实体，
 * 以 {@code centerOffset != null}（initData 完成标志）为门槛触发一次性重建。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_PhysicsOnlyCollisionEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 已重建过的载具（key = 实体实例）。用 WeakHashMap 存实体对象而非 UUID：
     * 单机下服务端实体与客户端实体是不同实例但 UUID 相同，按 UUID 标记会互相误判跳过；
     * 实体被 GC 后条目自动清理。
     */
    private static final Map<AbstractVehicle, Boolean> REBUILT = Collections.synchronizedMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        // 仅清除重建标志：载具刚加入世界时 initData 尚未执行（centerOffset=null、bodyCubes 为空），
        // 重建延后到每 tick 遍历时由 centerOffset 门槛保证数据就绪。
        REBUILT.remove(vehicle);
    }

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            REBUILT.remove(vehicle);
            RVP_PhysicsOnlyCollisionHelper.onVehicleLeave(vehicle);
        }
    }

    /** 载具 initData 完成后触发一次 physics-only 重建（返回是否执行了重建）。 */
    private static void rebuildIfPending(AbstractVehicle vehicle) {
        // initData 完成标志：centerOffset 由 initData(BaseVehicleData) 填充，
        // 非 null 说明 vehicleCubeOBBs/centerOffset 已就绪，可安全重建。
        if (vehicle.centerOffset == null) {
            return;
        }
        if (REBUILT.put(vehicle, Boolean.TRUE) != null) {
            return;
        }
        try {
            RVP_PhysicsOnlyCollisionHelper.rebuildPhysicsOnlyCubes(vehicle);
        } catch (Throwable t) {
            // 防御：physics-only 初始化失败不允许阻断实体，否则带 physics_only_bone 的载具会“放不出来”。
            LOGGER.error("[RVP-PhysicsOnly] 载具 {} 重建 physics-only 盒异常（已忽略，不阻断生成）", vehicle.getVehicleId(), t);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    rebuildIfPending(vehicle);
                }
            }
            RVP_PhysicsOnlyCollisionHelper.tickVehicles(level);
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle) {
                rebuildIfPending(vehicle);
            }
        }
        RVP_PhysicsOnlyCollisionHelper.tickClientVehicles(level);
    }
}
