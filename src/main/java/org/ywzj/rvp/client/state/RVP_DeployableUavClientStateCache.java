package org.ywzj.rvp.client.state;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅保存服务端权威下发的母车辅助载具选择与锁状态。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DeployableUavClientStateCache {
    /** 客户端展示用状态快照；不存在时 get 返回 null。 */
    public record StateSnapshot(String selectedOptionId, long revision, int remainingCooldownTick,
                                boolean deployed, String lockedReason) {}
    private static final Map<UUID, StateSnapshot> STATES = new ConcurrentHashMap<>();
    private RVP_DeployableUavClientStateCache() {}

    /** 读取最近收到的服务端快照；未知时返回 null，禁止伪造 READY 状态。 */
    public static StateSnapshot get(UUID parentUuid) { return parentUuid == null ? null : STATES.get(parentUuid); }

    /** 按 revision 丢弃迟到旧包，只接受更新的权威状态。 */
    public static void applySnapshot(UUID parentUuid, StateSnapshot snapshot) {
        if (parentUuid == null || snapshot == null) return;
        STATES.compute(parentUuid, (uuid, previous) -> previous == null || snapshot.revision() >= previous.revision()
                ? snapshot : previous);
    }

    /** 登出或客户端维度世界卸载时清除缓存，避免跨世界复用母车 UUID 快照。 */
    @SubscribeEvent public static void onClientLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) clear();
    }

    /** 客户端断开服务端时清除所有母车状态。 */
    @SubscribeEvent public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    /** 清理客户端权威状态镜像。 */
    public static void clear() { STATES.clear(); }
}