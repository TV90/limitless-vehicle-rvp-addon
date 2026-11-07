package org.ywzj.rvp.client.state;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.event.RVP_RotaryFlightMode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端直升机三态缓存，按载具体现实体 ID 索引。
 * <p>服务端在模式变化与玩家开始追踪载具时下发 {@code S2CRotaryFlightModeSync}；
 * 动作条只读本缓存，不在客户端重复推断 Z 键循环。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RVP_ClientRotaryFlightModeState {

    /** 实体 ID → 已同步的飞行模式。 */
    private static final Map<Integer, RVP_RotaryFlightMode> MODES = new ConcurrentHashMap<>();

    private RVP_ClientRotaryFlightModeState() {}

    /** 写入服务端同步的序号；越界按常规机动处理，避免坏包影响 HUD。 */
    public static void apply(int vehicleEntityId, int ordinal) {
        RVP_RotaryFlightMode[] values = RVP_RotaryFlightMode.values();
        MODES.put(vehicleEntityId, ordinal >= 0 && ordinal < values.length
                ? values[ordinal] : RVP_RotaryFlightMode.NORMAL);
    }

    /** 读取当前模式；尚未收到同步时按常规机动显示。 */
    public static RVP_RotaryFlightMode get(int vehicleEntityId) {
        return MODES.getOrDefault(vehicleEntityId, RVP_RotaryFlightMode.NORMAL);
    }

    /** 实体离开客户端世界时清理，防止实体 ID 复用导致旧模式串台。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity().level().isClientSide()) {
            MODES.remove(event.getEntity().getId());
        }
    }

    /** 切换维度/退出世界时整体清空。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            MODES.clear();
        }
    }
}
