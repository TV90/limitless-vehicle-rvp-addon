package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.misc.VehiclePart;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 残件/飞头诊断日志（2026-10-10，排查 ztz99b 概率飞头：不飞 / 击毁直接飞 / 殉燃末才飞）。
 *
 * <p>服务端两条记录，回答飞头排查的两个关键问题：</p>
 * <ul>
 *   <li><b>整车死亡表</b>：载具 isDestroyed 后输出全部部件的
 *       detachable / destroyed / detached 状态表——飞与不飞的判定依据直接可读
 *       （detachable=false=不可飞；detachable=true+detached=false=未飞；
 *       detached=true=已飞，对应场上残件）。</li>
 *   <li><b>残件生成事件</b>：每个 VehiclePart 实体生成时记录部件 id 与坐标
 *       （服务端真相：有行=服务端确实生成了残件；无行=根本没 detach）。</li>
 * </ul>
 * <p>每辆载具只输出一次状态表（按 vehicleId 去重，弱引用随实体回收）。
 * 问题定位后整类删除即回退。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckDiag {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已输出过状态表的死亡载具实体 id（弱引用集，实体回收自动清理）。 */
    private static final Set<Integer> DUMPED = Collections.newSetFromMap(new WeakHashMap<>());

    private RVP_WreckDiag() {
    }

    /** 整车死亡状态表：服务端每秒扫描一次，死亡且未输出过的载具输出全部件状态。 */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !(event.level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        for (var entity : serverLevel.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || entity instanceof VehiclePart
                    || !vehicle.isDestroyed()
                    || !vehicle.isAlive()
                    || !DUMPED.add(vehicle.getId())) {
                continue;
            }
            StringBuilder sb = new StringBuilder("[RVP-WreckDiag] vehicleId=").append(vehicle.getId())
                    .append(" @(")
                    .append(String.format("%.0f", vehicle.getX())).append(',')
                    .append(String.format("%.0f", vehicle.getY())).append(',')
                    .append(String.format("%.0f", vehicle.getZ()))
                    .append(") parts:");
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (partUnit == null) {
                    continue;
                }
                sb.append("\n  ").append(partUnit.getId())
                  .append(" detachable=").append(partUnit.isDetachable())
                  .append(" destroyed=").append(partUnit.isDestroyed())
                  .append(" detached=").append(partUnit.isDetached());
            }
            LOGGER.info(sb.toString());
        }
    }

    /** 残件实体生成：部件 id + 残件实体 id + 坐标（服务端真相）。 */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehiclePart part)) {
            return;
        }
        String pid = (part.getPartUnit() != null) ? part.getPartUnit().getId() : "?";
        LOGGER.info("[RVP-WreckDiag] SPAWN part={} wreckId={} @({},{},{})",
                pid, part.getId(),
                String.format("%.0f", part.getX()), String.format("%.0f", part.getY()),
                String.format("%.0f", part.getZ()));
    }
}
