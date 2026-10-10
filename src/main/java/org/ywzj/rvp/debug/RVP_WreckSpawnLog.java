package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.slf4j.Logger;

/**
 * 残件（VehiclePart 飞头）生成诊断日志（2026-10-10，排查 ztz99b 概率飞头）。
 * 服务端每生成一个残件打一行：部件 id / 坐标 / 飞行 tick。
 * 定位问题后可整类删除或静音。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckSpawnLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_WreckSpawnLog() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehiclePart part)) {
            return;
        }
        String pid = (part.getPartUnit() != null) ? part.getPartUnit().getId() : "unknown";
        LOGGER.info("[RVP-WreckSpawn] part={} pos=({},{},{}) tick={}",
                pid,
                String.format("%.0f", part.getX()), String.format("%.0f", part.getY()),
                String.format("%.0f", part.getZ()),
                part.tickCount);
    }
}
