package org.ywzj.rvp.uav;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_DeployableUavCatalog;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CAuxiliaryHudContext;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 当前乘员专用同步：换车立即推送；父/子机每十 tick 更新，普通车不持续发包。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_AuxiliaryHudSyncService {
    /** 每名乘员上次乘坐的实例，用于换车当 tick 立即推送。 */
    private static final Map<UUID, UUID> LAST_VEHICLES = new HashMap<>();
    /** 记住上次已发送身份，配置重载从母车变回普通车时也必须撤掉旧入口。 */
    private static final Map<UUID, RVP_AuxiliaryHudSnapshot.Role> LAST_ROLES = new HashMap<>();
    private RVP_AuxiliaryHudSyncService() {}

    @SubscribeEvent public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            LAST_VEHICLES.remove(player.getUUID());
            LAST_ROLES.remove(player.getUUID());
            return;
        }
        boolean changed = !vehicle.getUUID().equals(LAST_VEHICLES.put(player.getUUID(), vehicle.getUUID()));
        if (!changed && player.tickCount % 10 != 0) return;
        var role = RVP_AuxiliaryHudSnapshot.classify(RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle),
                RVP_DeployableUavConfigCache.get(vehicle.getVehicleId()).isConfigured());
        if (!changed && role == RVP_AuxiliaryHudSnapshot.Role.ORDINARY
                && role == LAST_ROLES.get(player.getUUID())) return;
        LAST_ROLES.put(player.getUUID(), role);
        var state = snapshot(player, vehicle, role);
        RVP_Network.CHANNEL.sendTo(new S2CAuxiliaryHudContext(state), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    /** 与实际部署/切换入口共用父子租约、选项和座位许可，UI 不创造第二套控制规则。 */
    private static RVP_AuxiliaryHudSnapshot snapshot(ServerPlayer player, AbstractVehicle vehicle,
                                                     RVP_AuxiliaryHudSnapshot.Role role) {
        if (role == RVP_AuxiliaryHudSnapshot.Role.CHILD) {
            return new RVP_AuxiliaryHudSnapshot(vehicle.getUUID(), role, 0, true, false,
                    RVP_LinkedUavStateTable.isDeployableUavControlSwitchAllowed(vehicle));
        }
        if (role == RVP_AuxiliaryHudSnapshot.Role.ORDINARY)
            return new RVP_AuxiliaryHudSnapshot(vehicle.getUUID(), role, 0, false, false, false);
        var level = (ServerLevel) vehicle.level();
        String option = RVP_DeployableUavCatalog.getSelectedOptionId(level, vehicle.getUUID(), vehicle.getVehicleId());
        var state = RVP_DeployableUavStateSavedData.snapshot(level, vehicle.getUUID(), option);
        boolean deployed = state.deployed() || RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(vehicle) != null;
        boolean operator = RVP_DeployableUavService.isDeployableControlOperator(vehicle, player);
        var child = RVP_DeployableUavService.getLinkedChild(vehicle).orElse(null);
        boolean switchAllowed = operator && child != null && child.isAlive() && !child.isDestroyed()
                && RVP_LinkedUavStateTable.isDeployableUavControlSwitchAllowed(child)
                && !RVP_DeployableUavService.isUavOutOfSignalRange(child);
        boolean deployAllowed = operator && !deployed && state.remainingCooldownTick() <= 0 && state.lockedReason().isEmpty();
        return new RVP_AuxiliaryHudSnapshot(vehicle.getUUID(), role, state.remainingCooldownTick(), deployed,
                deployAllowed, switchAllowed);
    }

    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_VEHICLES.remove(event.getEntity().getUUID());
        LAST_ROLES.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void onServerStopped(ServerStoppedEvent event) { LAST_VEHICLES.clear(); LAST_ROLES.clear(); }
}
