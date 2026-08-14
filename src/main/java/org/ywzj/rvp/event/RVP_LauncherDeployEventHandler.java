package org.ywzj.rvp.event;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.LauncherDeployStateMachine;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 发射架部署状态机的驱动。
 *
 * <p>服务端与客户端各跑一份确定性状态机：每 tick 对所有载具调用
 * {@link LauncherDeployStateMachine#tick}（状态推进 / Switchable 同步 / 快照写入 /
 * 驱动发射架部件 {@code xRot/xAimRot}）。俯仰以本体风格（htf5980 同款：驱动部件
 * xRot → 本体 updateRot 旋转结构骨 launcher_pitch_barrel）实现，OBB 双端跟随，
 * 无 Mixin。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_LauncherDeployEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            LauncherDeployStateMachine.clear(vehicle.getId());
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
            tickVehicles(level);
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            tickClientVehicles(level);
        }
    }

    /**
     * 渲染帧前诊断发射架武器站实际旋转角（每 30 tick）：
     * 实际姿态由状态机驱动部件 {@code xRot} 经本体 {@code updateRot} 写结构骨实现，
     * 这里仅保留日志便于验证 OBB / 渲染是否跟随。
     */
    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle && vehicle.tickCount % 30 == 0) {
                diagnoseLauncherAngle(vehicle);
            }
        }
    }

    /** 渲染时刻诊断：打印发射架武器站 xTurnGroup 实际旋转角（每 30 tick）。 */
    @OnlyIn(Dist.CLIENT)
    private static void diagnoseLauncherAngle(AbstractVehicle vehicle) {
        for (org.ywzj.rvp.config.RVP_LauncherDeployConfig config :
                org.ywzj.rvp.config.RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            org.ywzj.vehicle.vehicle.part.PartUnit<?> part = vehicle.getPartUnit(config.pitchPartUnitId()).orElse(null);
            if (!(part instanceof org.ywzj.vehicle.vehicle.part.WeaponUnit wu)) {
                continue;
            }
            LOGGER.info("[RVP-LaunchDeploy] 渲染时刻 载具={} part={} xRot={} xTurnGroup角度={}",
                    vehicle.getVehicleId(), part.getId(), wu.getXRot(),
                    org.ywzj.rvp.config.LauncherDeployPoseHelper.getXTurnGroupAngleDeg(wu));
        }
    }

    private static void tickVehicles(ServerLevel level) {
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity instanceof AbstractVehicle vehicle) {
                LauncherDeployStateMachine.tick(vehicle);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void tickClientVehicles(ClientLevel level) {
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle) {
                LauncherDeployStateMachine.tick(vehicle);
            }
        }
    }
}
