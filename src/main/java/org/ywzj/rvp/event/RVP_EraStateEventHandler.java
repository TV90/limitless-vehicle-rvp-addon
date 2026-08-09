package org.ywzj.rvp.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.RVP_EraStateSavedData;
import org.ywzj.rvp.vehicle.RVP_EraStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;
import java.util.Set;

/**
 * ERA 状态侧表与独立存档的桥接：
 * <ul>
 *   <li>载具加入世界 → 从 {@link RVP_EraStateSavedData} 恢复失效骨块集合进内存侧表；</li>
 *   <li>载具离开世界 → 把内存侧表写回存档并清理内存条目。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_EraStateEventHandler {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        List<String> saved = RVP_EraStateSavedData.get(serverLevel).readEntry(vehicle.getUUID());
        if (saved != null && !saved.isEmpty()) {
            RVP_EraStateTable.setInactiveEraBones(vehicle.getUUID(), saved);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Set<String> bones = RVP_EraStateTable.getInactiveEraBones(vehicle.getUUID());
        if (!bones.isEmpty()) {
            RVP_EraStateSavedData.get(serverLevel).writeEntry(vehicle.getUUID(), bones);
        }
        RVP_EraStateTable.onVehicleLeave(vehicle.getUUID());
    }
}
