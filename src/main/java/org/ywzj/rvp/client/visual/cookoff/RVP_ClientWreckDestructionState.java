package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.S2CWreckDestructionState;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端权威击毁快照缓存；资源重载只清效果绑定，不清这份服务端决定。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientWreckDestructionState {
    /** 当前客户端世界身份；单机也与服务端管理器完全分表。 */
    private static ClientLevel currentLevel;
    /** 允许先于实体生成包抵达的 UUID 快照；实体离开时清理，重新追踪由服务端补发。 */
    private static final Map<UUID, RVP_WreckDestructionState> STATES = new HashMap<>();

    private RVP_ClientWreckDestructionState() {}

    /** 桥接入口只接收当前维度的数据；延迟分支执行标记更新不会重启效果。 */
    public static void accept(S2CWreckDestructionState message) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(message.dimension())) return;
        if (currentLevel != level) {
            STATES.clear();
            currentLevel = level;
        }
        STATES.put(message.vehicleId(), message.state());
    }

    /** 无快照时返回 null，控制器等待权威结果，避免立即飞头车先闪一帧殉燃。 */
    public static RVP_WreckDestructionState get(AbstractVehicle vehicle) {
        return vehicle.level() == currentLevel ? STATES.get(vehicle.getUUID()) : null;
    }

    /** 客户端实体卸载/移除时释放条目，不能无限累积战场残骸 UUID。 */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() == currentLevel) STATES.remove(event.getEntity().getUUID());
    }

    /** 退出或切世界立即清空；F3+T 不卸载世界，保留快照供效果重建。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == currentLevel) {
            STATES.clear();
            currentLevel = null;
        }
    }
}
