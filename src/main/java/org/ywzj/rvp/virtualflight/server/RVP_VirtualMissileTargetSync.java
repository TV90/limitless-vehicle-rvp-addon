package org.ywzj.rvp.virtualflight.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CVirtualMissileTargets;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarScanHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 虚拟目标的独立同步服务，不依赖原版实体追踪距离，也不创建世界实体。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VirtualMissileTargetSync {
    /** 坐标同步周期，单位游戏刻；与服务端接触保活周期一致。 */
    private static final int SYNC_INTERVAL_TICKS = 2;
    /** 每位观察者上次收到的目标身份，用来区分恢复实体与真正失效。 */
    private static final Map<UUID, Map<Integer, UUID>> PREVIOUS_TARGETS = new HashMap<>();

    /** 工具类不允许实例化。 */
    private RVP_VirtualMissileTargetSync() {}

    /** 雷达服务完成后发送，避免客户端读取前一刻的位置与发现结果。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % SYNC_INTERVAL_TICKS != 0) return;
        // 调用逐维度同步入口，维度间的目标绝不混发。
        for (ServerLevel level : event.getServer().getAllLevels()) syncLevel(level);
    }

    /** 状态转换后可立即推送；正常巡航由低频 Tick 推送。 */
    public static void syncLevel(ServerLevel level) {
        // 调用权威目标源一次，所有同维度玩家共享此次记录快照。
        List<RVP_MissileEntity> virtual = RVP_VirtualMissileManager.targets(level);
        for (ServerPlayer player : level.players()) {
            Map<Integer, Entity> targets = new LinkedHashMap<>();
            if (player.getVehicle() instanceof AbstractVehicle vehicle) {
                // 调用本车扫描包络筛选与中继链查询，只向实际具有雷达来源的玩家发送。
                appendTargets(vehicle, virtual, targets);
                RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(vehicle)
                        .ifPresent(relay -> appendTargets(relay, virtual, targets));
            }
            Map<Integer, UUID> previous = PREVIOUS_TARGETS.getOrDefault(player.getUUID(), Map.of());
            if (targets.isEmpty() && previous.isEmpty()) continue;
            Set<Integer> restored = new HashSet<>();
            for (var entry : previous.entrySet()) {
                if (targets.containsKey(entry.getKey())) continue;
                Entity real = level.getEntity(entry.getValue());
                if (real != null && real.isAlive() && real.getId() == entry.getKey()) restored.add(entry.getKey());
            }
            List<S2CVirtualMissileTargets.Entry> entries = new ArrayList<>();
            Map<Integer, UUID> identities = new HashMap<>();
            for (Entity entity : targets.values()) {
                RVP_MissileEntity missile = (RVP_MissileEntity) entity;
                CompoundTag data = new CompoundTag();
                missile.saveWithoutId(data);
                // 调用本体远程弹体接口，携带武器 ID、所有者和真实分角度信号所需数据。
                missile.writeData(data);
                // 调用统一高度入口；地形查询暂关时只发送占位值 0，保留原协议与开启后的同步路径。
                entries.add(new S2CVirtualMissileTargets.Entry(missile.getId(), data,
                        RVP_VirtualMissileTargets.groundHeight(missile, Heightmap.Types.MOTION_BLOCKING)));
                identities.put(missile.getId(), missile.getUUID());
            }
            PREVIOUS_TARGETS.put(player.getUUID(), identities);
            // 调用 RVP 单向服务端网络通道，客户端只消费快照，不能修改虚拟记录。
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CVirtualMissileTargets(level.dimension().location(), entries, restored));
        }
    }

    /** 仅传输雷达包络内目标；真正的扫描线、接触保持和锁定仍由原雷达逻辑处理。 */
    private static void appendTargets(AbstractVehicle vehicle, List<RVP_MissileEntity> virtual,
                                      Map<Integer, Entity> out) {
        // 调用本体动力状态检查，失效载具不再授权新的雷达目标。
        if (vehicle.isDestroyed() || !vehicle.hasPower()) return;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof RadarUnit radar) || !radar.isOn() || RVP_RadarScanHelper.isVehicleOnly(radar)) continue;
            for (RVP_MissileEntity target : virtual) {
                // 调用真实弹药共用包络：信号、距离、离地高度和方位限位完全相同。
                // 已有接触交给雷达原有保活规则裁剪，信号角度变化不能让同步层提前掐断旧航迹。
                if (RVP_RadarScanHelper.isWithinIlluminationEnvelope(radar, target)
                        || radar.getDetectedEntities().containsKey(target.getId())) out.put(target.getId(), target);
            }
        }
    }

    /** 离线玩家不保留目标授权，重新进入时发送全新集合。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PREVIOUS_TARGETS.remove(event.getEntity().getUUID());
    }

    /** 清除仅运行时的观察者缓存，避免单人游戏重开世界后沿用旧 ID。 */
    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) { PREVIOUS_TARGETS.clear(); }
}
