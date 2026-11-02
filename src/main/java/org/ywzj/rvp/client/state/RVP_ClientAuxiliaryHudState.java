package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import java.util.Objects;
import java.util.UUID;

/** 只接受当前乘坐实例的权威 HUD 快照；换车/下车/换维度不复用旧身份。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientAuxiliaryHudState {
    /** 当前客户端正在乘坐的实例身份；不以车型 ID 作为缓存键。 */
    private static UUID observedVehicle;
    /** 最近一次通过当前乘坐身份校验的服务端快照。 */
    private static RVP_AuxiliaryHudSnapshot snapshot;
    /** 快照到达的客户端世界游戏 tick，用于包间平滑倒计时。 */
    private static long receivedTick;
    private RVP_ClientAuxiliaryHudState() {}

    public static void apply(RVP_AuxiliaryHudSnapshot value) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !(mc.player.getVehicle() instanceof AbstractVehicle vehicle)
                || !vehicle.getUUID().equals(value.vehicleUuid())) return;
        observedVehicle = value.vehicleUuid(); snapshot = value; receivedTick = mc.level.getGameTime();
    }

    /** 未同步身份时返回空，宁可暂时只显示地图/状态，也不把子机误当作母车。 */
    public static RVP_AuxiliaryHudSnapshot get(UUID vehicleUuid, long currentTick) {
        return snapshot != null && Objects.equals(snapshot.vehicleUuid(), vehicleUuid)
                ? snapshot.elapsed(receivedTick, currentTick) : null;
    }

    @SubscribeEvent public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var player = Minecraft.getInstance().player;
        UUID current = player != null && player.getVehicle() instanceof AbstractVehicle vehicle ? vehicle.getUUID() : null;
        if (!Objects.equals(current, observedVehicle)) { clear(); observedVehicle = current; }
    }
    @SubscribeEvent public static void onUnload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clear(); }
    @SubscribeEvent public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    private static void clear() { snapshot = null; observedVehicle = null; receivedTick = 0; }
}
