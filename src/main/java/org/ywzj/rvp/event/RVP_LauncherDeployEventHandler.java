package org.ywzj.rvp.event;

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
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.LauncherDeployStateMachine;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 发射架部署状态机的驱动（替代被删的 {@code AbstractVehicleLauncherDeployMixin} tick 注入
 * 与 {@code WeaponUnitLauncherDeployPoseBypassMixin} 姿态旁路）。
 *
 * <p>服务端与客户端各跑一份确定性状态机：每 tick 对所有载具推进
 * {@link LauncherDeployStateMachine#tick}（状态推进 / Switchable 同步 / 俯仰角应用），
 * 随后调用 {@link LauncherDeployStateMachine#applyWeaponUnitPose} 在所有武器 tick 之后
 * 应用发射架俯仰（姿态旁路），保证武器自身旋转逻辑不会覆盖部署姿态。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_LauncherDeployEventHandler {

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

    private static void tickVehicles(ServerLevel level) {
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity instanceof AbstractVehicle vehicle) {
                LauncherDeployStateMachine.tick(vehicle);
                LauncherDeployStateMachine.applyWeaponUnitPose(vehicle);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void tickClientVehicles(ClientLevel level) {
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle) {
                LauncherDeployStateMachine.tick(vehicle);
                LauncherDeployStateMachine.applyWeaponUnitPose(vehicle);
            }
        }
    }
}
