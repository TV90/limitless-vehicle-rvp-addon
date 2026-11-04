package org.ywzj.rvp.virtualflight.server;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.Departure;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.DepartureReason;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.Entry;
import org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.MotorPhase;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读虚拟弹显示同步；不查地形、不创建计算对象、不调用运动或恢复方法。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VirtualMissileVisualSync {
    /** 任意同维度玩家可看的水平范围，单位格。 */
    public static final double NEAR_RANGE = 4_096;
    /** 归属或已有雷达发现可延伸到的水平范围，单位格。 */
    public static final double FAR_RANGE = 8_192;
    /** 每次服务器运行随机生成的视觉会话身份，不参与存档。 */
    private static UUID sessionId = UUID.randomUUID();
    /** 每观察者的上一完整集合及维度，用于撤销与恢复交接。 */
    private static final Map<UUID, Observer> OBSERVERS = new HashMap<>();
    /** 生命周期发生变化的维度，本刻末即使不是常规发送刻也刷新。 */
    private static final Set<ResourceLocation> DIRTY_LEVELS = new HashSet<>();

    private RVP_VirtualMissileVisualSync() { }

    /** 状态已提交后标脏，合并同一刻的终止、拦截和恢复刷新。 */
    public static void requestSync(ServerLevel level) { DIRTY_LEVELS.add(level.dimension().location()); }

    /** 雷达和上行改靶提交后读取完成态；每维度只提取一次候选，玩家间共享不可变样本。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            boolean dirty = DIRTY_LEVELS.remove(level.dimension().location());
            if (dirty || event.getServer().getTickCount() % 2 == 0) syncLevel(level);
        }
    }

    /** 转虚拟可在真实弹移除前预告；本方法始终只读当时已经提交的权威结果。 */
    public static void syncLevel(ServerLevel level) {
        long start = System.nanoTime();
        List<Candidate> candidates = new ArrayList<>();
        // 调用权威容器的只读集合，绝不走 getOrCreateEntity 或轨迹步进。
        var saved = RVP_VirtualMissileSavedData.get(level);
        for (RVP_VirtualMissileState state : saved.states()) {
            RVP_MissileEntity missile = state.trajectoryEntity;
            if (!state.dimension.equals(level.dimension().location()) || missile == null
                    || !missile.isAlive() || state.trajectoryEntityInvalidated) continue;
            try {
                // 调用只读显示提取；没有有效缓存对象的异常记录交由原管理器处理。
                candidates.add(new Candidate(sample(missile, state.phase), state.ownerUuid, state.shooterVehicleUuid));
            } catch (IllegalArgumentException exception) {
                // 无效视觉数据不影响权威弹道，也不在同步层补算。
            }
        }
        List<Candidate> immutableCandidates = List.copyOf(candidates);
        int sentEntries = 0;
        int sentBytes = 0;
        for (ServerPlayer player : level.players()) {
            Observer observer = OBSERVERS.computeIfAbsent(player.getUUID(), ignored -> new Observer());
            if (!level.dimension().location().equals(observer.dimension)) {
                observer.previous = Map.of();
                observer.dimension = level.dimension().location();
            }
            AbstractVehicle vehicle = player.getVehicle() instanceof AbstractVehicle current ? current : null;
            Set<UUID> discovered = new HashSet<>();
            Set<UUID> locked = new HashSet<>();
            if (vehicle != null) {
                // 调用已完成雷达接触查询，显示服务不另做扫描或高度图访问。
                collectContacts(vehicle, discovered, locked);
                RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(vehicle)
                        .ifPresent(relay -> collectContacts(relay, discovered, locked));
            }
            List<Choice> choices = new ArrayList<>();
            for (Candidate candidate : immutableCandidates) {
                Entry entry = candidate.entry();
                double dx = entry.position().x - player.getX();
                double dz = entry.position().z - player.getZ();
                double distanceSquared = dx * dx + dz * dz;
                boolean owned = player.getUUID().equals(candidate.ownerUuid())
                        || vehicle != null && vehicle.getUUID().equals(candidate.vehicleUuid());
                if (isVisible(distanceSquared, owned, discovered.contains(entry.flightUuid()))) {
                    choices.add(new Choice(entry, owned, locked.contains(entry.flightUuid()),
                            distanceSquared, observer.previous.containsKey(entry.flightUuid())));
                }
            }
            // 调用稳定预算排序，锁定、本人、距离优先级内保留上一批；最后以 UUID 打破平局。
            List<Entry> selected = select(choices);
            List<Departure> departures = departures(level, saved, observer.previous, selected);
            // 完整集合不拆包；按真实编码长度裁剪，离开项的最终恢复样本同样占预算。
            while (S2CVirtualMissileVisualSnapshot.encodedSize(observer.dimension, selected, departures)
                    > S2CVirtualMissileVisualSnapshot.MAX_PACKET_BYTES && !selected.isEmpty()) {
                selected.remove(selected.size() - 1);
                departures = departures(level, saved, observer.previous, selected);
            }
            if (selected.isEmpty() && observer.previous.isEmpty()) continue;
            var message = new S2CVirtualMissileVisualSnapshot(observer.dimension, sessionId,
                    ++observer.sequence, level.getGameTime(), selected, departures);
            Map<UUID, Entry> identities = new LinkedHashMap<>();
            for (Entry entry : selected) identities.put(entry.flightUuid(), entry);
            observer.previous = Map.copyOf(identities);
            sentEntries += selected.size();
            sentBytes += S2CVirtualMissileVisualSnapshot.encodedSize(observer.dimension, selected, departures);
            // 调用固定 S2C 通道；视觉权限不写入雷达接触或服务端可锁目标。
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
        // 调用既有虚拟飞行诊断开关，每十秒汇总一次，默认不打印逐弹日志。
        if (RVP_VirtualMissileDebug.isEnabled() && level.getServer().getTickCount() % 200 == 0) {
            LogUtils.getLogger().info("[RVP][VirtualVisual] dimension={} candidates={} entries={} bytes={} nanos={}",
                    level.dimension().location(), candidates.size(), sentEntries, sentBytes, System.nanoTime() - start);
        }
    }

    /** 视觉范围纯判定，不依赖载具、雷达扫描、地形或区块。 */
    static boolean isVisible(double distanceSquared, boolean owned, boolean discovered) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= FAR_RANGE * FAR_RANGE
                && (distanceSquared <= NEAR_RANGE * NEAR_RANGE || owned || discovered);
    }

    /** 距离按每 256 格一档；同档保留上一集合，避免几格差距反复争夺最后一个预算名额。 */
    static List<Entry> select(List<Choice> choices) {
        return new ArrayList<>(choices.stream().sorted(Comparator
                .comparing(Choice::locked).reversed()
                .thenComparing(Choice::owned, Comparator.reverseOrder())
                .thenComparingLong(choice -> (long) (Math.sqrt(choice.distanceSquared()) / 256))
                .thenComparing(Choice::retained, Comparator.reverseOrder())
                .thenComparingDouble(Choice::distanceSquared)
                .thenComparing(choice -> choice.entry().flightUuid()))
                .limit(S2CVirtualMissileVisualSnapshot.MAX_ENTRIES).map(Choice::entry).toList());
    }

    /** 只读实体和完整快照；当前窗口与最晚燃尽时间分开，保留燃尽边界的实际阶段。 */
    static Entry sample(RVP_MissileEntity missile, RVP_VirtualFlightPhase phase) {
        // 调用正式实体只读快照和燃烧查询，不初始化配置或运行飞行逻辑。
        var snapshot = missile.createVirtualMidcourseSnapshot();
        var trajectory = snapshot.trajectory();
        var data = missile.getRvpData();
        boolean burning = data != null && data.getProjectileData().hasRocketEngine() && missile.isMotorBurningNow();
        boolean second = burning && missile.isSecondMotorPulseBurningNow();
        long endTick = second ? (long) trajectory.secondPulseStartTick() + snapshot.secondPulseBurnTimeTick()
                : snapshot.motorBurnEndTick();
        int remaining = burning ? (int) Math.max(0, Math.min(S2CVirtualMissileVisualSnapshot.MAX_BURN_TICKS,
                endTick - trajectory.flightTick())) : 0;
        return new Entry(missile.getUUID(), missile.getId(), missile.getWeaponId(), trajectory.position(),
                trajectory.velocity(), trajectory.yRot(), trajectory.xRot(), trajectory.flightTick(), phase,
                !burning ? MotorPhase.IDLE : second ? MotorPhase.SECOND_PULSE : MotorPhase.PRIMARY,
                remaining, missile.ticksUntilMotorStopsBurning());
    }

    /** 仅从已存在的接触对象取 UUID，防同整数编号被别的实体复用。 */
    private static void collectContacts(AbstractVehicle vehicle, Set<UUID> discovered, Set<UUID> locked) {
        // 调用本体供电/部件状态，离车、关机后远距雷达资格自然撤销。
        if (vehicle.isDestroyed() || !vehicle.hasPower()) return;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof RadarUnit radar) || !radar.isOn()) continue;
            for (var contact : radar.getDetectedEntities().values()) {
                if (contact.entity != null && contact.entity.isAlive()) discovered.add(contact.entity.getUUID());
            }
            Entity target = radar.getLockedEntity();
            if (target != null && target.isAlive()) locked.add(target.getUUID());
        }
    }

    /** 离开原因只查询当前已加载实体和权威记录；实际恢复位置可能与虚拟终点不同。 */
    private static List<Departure> departures(ServerLevel level, RVP_VirtualMissileSavedData saved,
            Map<UUID, Entry> previous, List<Entry> selected) {
        Set<UUID> incoming = new HashSet<>();
        for (Entry entry : selected) incoming.add(entry.flightUuid());
        List<Departure> result = new ArrayList<>();
        for (Entry old : previous.values()) {
            if (incoming.contains(old.flightUuid())) continue;
            if (saved.contains(old.flightUuid())) {
                result.add(new Departure(old.flightUuid(), old.entityId(), DepartureReason.OUT_OF_SCOPE, null));
                continue;
            }
            Entity real = level.getEntity(old.flightUuid());
            if (real instanceof RVP_MissileEntity missile && missile.isAlive() && missile.getId() == old.entityId()) {
                // 调用真实入世弹的只读出口，最终模型落在恢复安全点，不沿用旧虚拟位置。
                result.add(new Departure(old.flightUuid(), old.entityId(), DepartureReason.RESTORED,
                        sample(missile, RVP_VirtualFlightPhase.RESTORE_REQUESTED)));
            } else result.add(new Departure(old.flightUuid(), old.entityId(), DepartureReason.TERMINATED, null));
        }
        return result;
    }

    /** 登出后不保留任何观察者集合或序号。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { OBSERVERS.remove(event.getEntity().getUUID()); }

    /** 换维度撤销上一世界集合，但序号在连接内继续递增。 */
    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        Observer observer = OBSERVERS.get(event.getEntity().getUUID());
        if (observer != null) { observer.previous = Map.of(); observer.dimension = null; }
        if (event.getEntity().level() instanceof ServerLevel level) requestSync(level);
    }

    /** 停服不持久化显示缓存，下次运行使用全新会话身份。 */
    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        OBSERVERS.clear(); DIRTY_LEVELS.clear(); sessionId = UUID.randomUUID();
    }

    /** @param entry 共享不可变样本 @param ownerUuid 保存的发射者 @param vehicleUuid 保存的发射车 */
    private record Candidate(Entry entry, UUID ownerUuid, UUID vehicleUuid) { }

    /**
     * @param entry 已通过视觉资格的样本
     * @param owned 本人或本车发射
     * @param locked 已有雷达锁定
     * @param distanceSquared 水平距离平方
     * @param retained 上一轮已显示
     */
    record Choice(Entry entry, boolean owned, boolean locked, double distanceSquared, boolean retained) { }

    /** 每个玩家连接的运行时观察者状态。 */
    private static final class Observer {
        /** 上一集合所在维度。 */
        private ResourceLocation dimension;
        /** 连接内递增，不因同刻刷新或换维度归零。 */
        private long sequence;
        /** 上一集合，最多 128 枚。 */
        private Map<UUID, Entry> previous = Map.of();
    }
}
