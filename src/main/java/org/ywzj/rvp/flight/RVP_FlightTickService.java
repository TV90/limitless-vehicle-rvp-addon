package org.ywzj.rvp.flight;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.flight.RVP_FlightTypes.*;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 新飞行会话的唯一调度：START先准备业务意图，再在本体物理更新前计算并执行。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FlightTickService {
    /** 调度异常只在状态变化时报告，防止每刻刷屏。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 业务准备回调，明确在计算阶段前执行，回调不能写运动字段。 */
    private static final Map<String, Consumer<MinecraftServer>> PRODUCERS = new LinkedHashMap<>();
    /** 按服务器实例隔离临时会话，停止时全部释放。 */
    private static final Map<MinecraftServer, ServerState> SERVERS = new IdentityHashMap<>();

    private RVP_FlightTickService() {}

    /** 注册业务的准备回调；应在模组common setup中调用一次。 */
    public static void registerProducer(String id, Consumer<MinecraftServer> producer) {
        Objects.requireNonNull(id); Objects.requireNonNull(producer);
        if (id.isBlank()) throw new IllegalArgumentException("飞行准备器名称不能为空");
        if (PRODUCERS.putIfAbsent(id, producer) != null) throw new IllegalArgumentException("重复飞行准备器: " + id);
    }

    /** 取得会话；同机有其他持有者则内部排队，授权回调仍由业务按自身身份规则验证。 */
    public static Session acquire(FixedWingVehicle vehicle, UUID owner, Predicate<FixedWingVehicle> authorization) {
        if (vehicle.level().isClientSide() || vehicle.getServer() == null) return null;
        MinecraftServer server = vehicle.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("飞行接管必须在服务端线程");
        ServerState state = SERVERS.computeIfAbsent(server, ignored -> new ServerState());
        WeakReference<FixedWingVehicle> reference = new WeakReference<>(vehicle);
        UUID operator = operator(vehicle);
        String dimension = vehicle.level().dimension().location().toString();
        // 授权来源由业务传入；额外锁定接管时的实际操作者，防止玩家换座后沿用旧授权。
        RVP_FlightSessions.Session lease = state.leases.acquire(vehicle.getUUID(), vehicle.level().dimension().location().toString(), owner,
                () -> {
                    FixedWingVehicle current = reference.get();
                    return current != null && current.isAlive() && !current.isRemoved() && !current.isDestroyed()
                            && current.getServer() == server && current.level().dimension().location().toString().equals(dimension)
                            && Objects.equals(operator(current), operator) && authorization.test(current);
                });
        if (lease == null) return null;
        return state.sessions.computeIfAbsent(lease, ignored -> new Session(server, reference, operator, lease, state));
    }

    /** 当前有效公共会话是否占有运动字段，旧写入者必须在此情况下退出。 */
    public static boolean controls(FixedWingVehicle vehicle) {
        ServerState state = SERVERS.get(vehicle.getServer());
        return state != null && state.leases.controlled(vehicle.getUUID(), vehicle.level().dimension().location().toString());
    }

    /** 供现有运动包守卫只读查询；公共会话接管期间保留功能键、屏蔽另一个运动写入者。 */
    public static boolean isControlLocked(org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle) {
        // 调用公共会话查询；非固定翼或没有有效会话时不影响原来的人工操纵。
        return vehicle instanceof FixedWingVehicle fixedWing && controls(fixedWing);
    }

    /** 业务离座/关闭时释放自己持有的会话。 */
    public static void release(FixedWingVehicle vehicle, UUID owner) {
        ServerState state = SERVERS.get(vehicle.getServer());
        if (state == null) return;
        for (Session session : new ArrayList<>(state.sessions.values())) {
            if (session.lease.vehicleId().equals(vehicle.getUUID()) && session.lease.owner().equals(owner)) session.close();
        }
    }

    /** 固定顺序调用生产者后只执行一次；不会依赖两个业务事件监听器的相对顺序。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        MinecraftServer server = event.getServer();
        ServerState state = SERVERS.computeIfAbsent(server, ignored -> new ServerState());
        if (state.lastTick == server.getTickCount()) return;
        state.lastTick = server.getTickCount();
        for (var entry : PRODUCERS.entrySet()) {
            try {
                entry.getValue().accept(server);
                state.producerFailures.remove(entry.getKey());
            } catch (RuntimeException exception) {
                String failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
                if (!failure.equals(state.producerFailures.put(entry.getKey(), failure)))
                    LOGGER.warn("[RVP Flight] 业务准备器{}失败: {}", entry.getKey(), failure);
            }
        }
        for (Session session : new ArrayList<>(state.sessions.values())) session.execute();
    }

    /** 在下车事件尚未更换操作者前清理本代次输入，任务存档仍由业务维护。 */
    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntityBeingMounted() instanceof FixedWingVehicle vehicle)) return;
        // 司机离开、或空司机位即将被占用时在身份变化前交还；普通乘客上下车不打断驾驶。
        if (event.isDismounting() ? vehicle.getDriver() != event.getEntityMounting() : vehicle.getDriver() != null) return;
        closeVehicle(vehicle);
    }

    /** 卸载不保留实体引用或控制凭据。 */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof FixedWingVehicle vehicle) closeVehicle(vehicle);
    }

    /** 世界关闭前释放全部临时状态，不触碰任何业务的持久任务。 */
    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        ServerState state = SERVERS.get(event.getServer());
        if (state != null) for (Session s : new ArrayList<>(state.sessions.values())) s.close();
        SERVERS.remove(event.getServer());
    }

    /** 关闭一架载具的当前会话。 */
    private static void closeVehicle(FixedWingVehicle vehicle) {
        ServerState state = SERVERS.get(vehicle.getServer());
        if (state != null) for (Session s : new ArrayList<>(state.sessions.values()))
            if (s.lease.vehicleId().equals(vehicle.getUUID())) s.close();
    }

    /** 实际司机身份；空驾驶位也是一种明确状态。 */
    private static UUID operator(FixedWingVehicle vehicle) {
        Entity entity = vehicle.getDriver();
        return entity == null ? null : entity.getUUID();
    }

    /** 服务端临时容器。 */
    private static final class ServerState {
        /** 排他身份表。 */ private final RVP_FlightSessions leases = new RVP_FlightSessions();
        /** 实际执行器，每服务器独立。 */ private final RVP_FlightExecutor executor = new RVP_FlightExecutor();
        /** 凭据到控制会话。 */ private final Map<RVP_FlightSessions.Session, Session> sessions = new LinkedHashMap<>();
        /** 已处理的服务器刻。 */ private long lastTick = Long.MIN_VALUE;
        /** 准备器异常差分记录，一个业务错误不能阻止其他飞机继续被控制。 */
        private final Map<String, String> producerFailures = new LinkedHashMap<>();
    }

    /** 业务可见控制会话；申请、更新和关闭均在服务端线程调用。 */
    public static final class Session {
        /** 所属服务器弱引用。 */ private final WeakReference<MinecraftServer> server;
        /** 载具弱引用。 */ private final WeakReference<FixedWingVehicle> vehicle;
        /** 申请时实际操作者。 */ private final UUID operator;
        /** 不可伪造的排他凭据。 */ private final RVP_FlightSessions.Session lease;
        /** 所属服务器临时容器。 */ private final ServerState owner;
        /** 公共尽量执行流程，保存控制状态、可靠观察和机体估算。 */
        private final RVP_FlightExecution execution = new RVP_FlightExecution();
        /** 上次记录异常类型，变化时才输出。 */ private String loggedFailure = "";

        private Session(MinecraftServer server, WeakReference<FixedWingVehicle> vehicle, UUID operator,
                        RVP_FlightSessions.Session lease, ServerState owner) {
            this.server = new WeakReference<>(server); this.vehicle = vehicle; this.operator = operator;
            this.lease = lease; this.owner = owner;
        }
        /** 保存/更新意图；窗口仅用于陈旧提示，超过后仍自动执行，不要求业务续租重试。 */
        public boolean submit(RVP_FlightRequest request, RVP_FlightTuning tuning, int validTicks) {
            MinecraftServer current = server.get();
            if (current == null || !current.isSameThread()) return false;
            return lease.submit(request, tuning, validTicks, current.getTickCount());
        }
        /** 是否仍有资格，包含内部等待中的会话，不代表本刻已经执行。 */ public boolean valid() { return lease.valid(); }
        /** 是否已轮到自己实际操纵；等待会话无需再次申请。 */ public boolean active() { return lease.current() && lease.valid(); }
        /** 最近反馈；调用者必须检查动作结果、tick和目标版本。 */ public Feedback feedback() { return execution.feedback(); }
        /** 最近动作状态。 */ public RVP_FlightExecutor.Result executionResult() { return execution.result(); }

        /** 交还只清自己代次与原操作者的残留，不能覆盖新玩家输入。 */
        public void close() {
            FixedWingVehicle current = vehicle.get();
            MinecraftServer currentServer = server.get();
            if (current != null && currentServer != null && lease.current() && Objects.equals(operator(current), operator)) {
                RVP_FlightVehicleAdapter port = new RVP_FlightVehicleAdapter(current, currentServer.getTickCount(),
                        generation -> lease.current() && lease.generation() == generation && Objects.equals(operator(current), operator));
                // 调用唯一执行边界清理运动输入，不触碰业务任务存档。
                try { if (port.available()) owner.executor.release(port, lease.generation()); }
                catch (RuntimeException exception) {
                    LOGGER.warn("[RVP Flight] 载具{}交还时端口已失效: {}", lease.vehicleId(), exception.toString());
                }
            }
            // 即便身份已变、不可再清实体输入，也要释放旧代次的消费元数据，避免UUID表长期增长。
            owner.executor.forget(lease.vehicleId(), lease.generation());
            lease.close(); owner.sessions.remove(lease);
        }

        /** 本体物理更新前，以本刻观察重新计算，不重放上一刻的补偿值。 */
        private void execute() {
            MinecraftServer currentServer = server.get();
            FixedWingVehicle current = vehicle.get();
            if (currentServer == null || current == null || !lease.valid()) { close(); return; }
            long tick = currentServer.getTickCount();
            RVP_FlightRequest request = lease.request(tick);
            if (!lease.current()) { execution.waiting(request, tick); return; }
            RVP_FlightVehicleAdapter port = new RVP_FlightVehicleAdapter(current, tick,
                    generation -> lease.generation() == generation && lease.current() && lease.valid());
            // 调用公共默认值补齐尚未提交的申请，接管后先稳飞；已提交目标永不因新鲜窗口到期丢弃。
            RVP_FlightRequest intent = request != null ? request : new RVP_FlightRequest("stabilize", 0, null, null, null, null);
            RVP_FlightTuning tuning = lease.tuning() != null ? lease.tuning() : RVP_FlightTuning.smoothCruise();
            // 调用可验证的执行流程，地形/机体暂缺先修正，异常由内部逐刻继续尝试原目标。
            execution.execute(owner.executor, port, lease.generation(),
                    (previous, terrain) -> port.observe(lease.generation(), intent, tuning, previous, terrain),
                    port::airframe, intent, tuning, request != null && !lease.fresh(tick));
            String failure = execution.failure();
            if (!failure.isEmpty() && !failure.equals(loggedFailure))
                LOGGER.warn("[RVP Flight] 载具{}自动修正/继续执行: {}", lease.vehicleId(), failure);
            loggedFailure = failure;
            // 仅身份/实体终止或会污染本体字段的致命数值禁止继续写；普通修正与暂时异常均保留会话。
            if (execution.result() != null && !execution.result().accepted()) close();
        }
    }
}
