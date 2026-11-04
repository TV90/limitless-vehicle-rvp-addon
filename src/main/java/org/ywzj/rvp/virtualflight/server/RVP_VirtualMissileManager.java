package org.ywzj.rvp.virtualflight.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.util.RVP_AimPointResolver;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightReason;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualTrajectoryResult;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 阶段 B 的服务端权威虚拟导弹状态机。
 *
 * <p>调用链以 {@link #onServerTick(TickEvent.ServerTickEvent)} 为入口：先从各维度
 * {@link RVP_VirtualMissileSavedData} 收集并校验记录，再推进巡航积分、挑选恢复候选、
 * 公平分配恢复 Ticket，最后执行 ready 检查和实体重建。类内所有世界查询都发生在
 * 服务端 Tick，且不会调用同步 {@code getChunk(...)}。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VirtualMissileManager {
    /** 单 Tick 最多允许多少枚导弹从巡航进入恢复队列。 */
    static final int MAX_RESTORE_ENTRIES_PER_TICK = 4;
    /** 单 Tick 全服务器最多新增多少个恢复区块 Ticket。 */
    static final int MAX_NEW_RESTORE_CHUNKS_PER_TICK = 16;
    /** {@code addFreshEntity}、碰撞安全点或 UUID 冲突允许的最大恢复尝试次数。 */
    static final int MAX_RESTORE_ATTEMPTS = 3;
    /** 两次实体恢复尝试之间的冷却时间，单位 Tick。 */
    static final int RESTORE_RETRY_INTERVAL_TICKS = 20;
    /** 与现有弹体路径保护一致、能够推进到 entity-ticking 的 Ticket level。 */
    private static final int RESTORE_TICKET_LEVEL = 2;
    /** UUID 为键、自然过期时间为 40 Tick 的恢复交接 Ticket。 */
    private static final TicketType<UUID> RESTORE_TICKET = TicketType.create(
            "rvp_virtual_missile_restore", Comparator.comparing(UUID::toString), 40);
    /** 跨 Tick 保留的恢复预算轮转起点，避免列表前部长期优先。 */
    private static int fairnessCursor;

    private RVP_VirtualMissileManager() {}

    /**
     * 在真实导弹完成本 Tick 运动、引信和路径提交后尝试原子转入虚拟态。
     *
     * <p>调用链：资格检查 → UUID 去重 → 创建完整快照 → 写入 SavedData →
     * 标记专用移除语义 → {@link Entity#discard()}。只有持久化记录注册成功才移除实体。</p>
     *
     * @param missile 仍处于服务端世界且存活的 RVP 类型化导弹
     * @return 成功注册并移除真实实体时为 {@code true}
     */
    public static boolean tryVirtualize(RVP_MissileEntity missile) {
        if (!(missile.level() instanceof ServerLevel level) || !missile.isAlive()) return false;
        RVP_WeaponData data = missile.getRvpData();
        if (data == null || missile.getWeaponId() == null) return false;
        // 调用资格组件集中完成静态配置和当前实体运行条件检查。
        RVP_VirtualMissileEligibility.Result eligibility = RVP_VirtualMissileEligibility.evaluate(missile, data);
        if (eligibility != RVP_VirtualMissileEligibility.Result.ELIGIBLE) {
            // 资格拒绝日志内部按实体去重，避免本方法每 Tick 重复刷屏。
            RVP_VirtualMissileDebug.eligibilityRejected(missile, eligibility);
            return false;
        }
        // 写入前跨维度检查稳定 UUID，保护 SavedData 主键唯一性。
        if (containsState(level.getServer(), missile.getUUID())) return false;

        Entity owner = missile.getOwner();
        AbstractVehicle shooterVehicle = missile.getShooterVehicle();
        long gameTime = level.getGameTime();
        RVP_VirtualMissileState state = new RVP_VirtualMissileState(
                missile.getUUID(), level.dimension().location(), missile.getWeaponId(),
                owner == null ? null : owner.getUUID(), shooterVehicle == null ? null : shooterVehicle.getUUID(),
                missile.getVirtualMidcourseLaunchPosition(), missile.createVirtualMidcourseSnapshot(),
                missile.createVirtualMidcourseKinematics(),
                missile.getId(), missile.getColdLaunchTimeTick(), gameTime, gameTime,
                RVP_EntityTrajectoryIntegrator.ID, RVP_EntityTrajectoryIntegrator.VERSION,
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        // [RVP] RADAR 上行改靶：标记与间隔从实体转快照（运行时字段，不入 NBT）
        state.radarUplinkTargeting = missile.radarUplinkTargeting;
        state.radarUplinkIntervalSecond = data == null
                ? 5.0f
                : org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS.resolveRadarUpdateIntervalSecond(
                        data.getGuidanceData());
        // 通过维度 DataStorage 取得权威容器；add 成功会立即 mark dirty。
        RVP_VirtualMissileSavedData savedData = RVP_VirtualMissileSavedData.get(level);
        if (!state.isStructurallyValid() || !savedData.add(state)) return false;
        state.targetEntityId = missile.getId();
        // 调用计算对象入口并交接已有雷达/拦截弹引用，真实弹移除前目标身份就已连续。
        RVP_MissileEntity target = RVP_EntityTrajectoryIntegrator.getOrCreateEntity(
                level, state, data, shooterVehicle, owner instanceof LivingEntity living ? living : null);
        // 本体按上一刻位移修正直击盒，转换当刻必须沿用原真实弹的位移历史。
        target.xOld = missile.xOld; target.yOld = missile.yOld; target.zOld = missile.zOld;
        RVP_VirtualMissileTargets.replaceReferences(level, missile, target);

        RVP_VirtualMissileDebug.noteEntered();
        RVP_VirtualMissileDebug.lifecycle(state, RVP_VirtualFlightReason.REAL_TO_VIRTUAL,
                "eligibility=" + eligibility);
        // 先标记专用移除原因，再调用普通 discard，让生命周期日志能识别转换语义。
        missile.beginVirtualMidcourseRemoval();
        // 先预告已登记的视觉身份，再移除真实弹，客户端同 UUID 来源可无缝接上。
        RVP_VirtualMissileVisualSync.syncLevel(level);
        missile.discard();
        // 调用独立同步服务，客户端先取得虚拟表示，再接收本体的真实实体移除包。
        RVP_VirtualMissileTargetSync.syncLevel(level);
        return true;
    }

    /**
     * 每个服务器 Tick START 驱动全部维度的虚拟状态机。
     *
     * @param event Forge 服务器 Tick 事件；END 阶段会被直接忽略
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        long managerStart = System.nanoTime();
        MinecraftServer server = event.getServer();
        // 第一步：加载各维度 SavedData，并清理结构损坏或跨维度重复 UUID。
        List<StateRef> refs = collectAndValidate(server);
        if (refs.isEmpty()) {
            RVP_VirtualMissileDebug.summarizeIfDue(server);
            return;
        }

        List<StateRef> restoreCandidates = new ArrayList<>();
        for (StateRef ref : refs) {
            RVP_VirtualMissileState state = ref.state;
            if (!state.runtimeInitialized) {
                state.runtimeInitialized = true;
                state.lastUpdatedGameTime = ref.level.getGameTime(); // 停服期间不推进
            }
            // 每 Tick 重新从当前 schema 索引解析配置，热重载删除后安全终止在途记录。
            RVP_WeaponData data = resolveWeapon(state.weaponId);
            if (data == null || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE) {
                terminate(ref, RVP_VirtualFlightReason.WEAPON_DATA_MISSING, "weapon not present/current RVP missile");
                continue;
            }
            // 调用当前准入检查；热重载引入世界依赖后不能让计算对象继续执行不支持的导引头或引信逻辑。
            if (RVP_VirtualMissileEligibility.evaluateConfiguration(data) != RVP_VirtualMissileEligibility.Result.ELIGIBLE) {
                terminate(ref, RVP_VirtualFlightReason.CONFIGURATION_CHANGED, "virtual flight configuration no longer eligible");
                continue;
            }
            if (!state.trajectoryImplementationId.equals(RVP_EntityTrajectoryIntegrator.ID)
                    || state.trajectoryStateVersion != RVP_EntityTrajectoryIntegrator.VERSION) {
                terminate(ref, RVP_VirtualFlightReason.INCOMPATIBLE_INTEGRATOR, "loaded state implementation mismatch");
                continue;
            }
            // 等待恢复的记录也必须保留可探测、可拦截的目标对象，读档后重新分配会话 ID。
            RVP_EntityTrajectoryIntegrator.getOrCreateEntity(ref.level, state, data,
                    resolveVehicle(ref.level, state.shooterVehicleUuid), resolveLivingEntity(ref.level, state.ownerUuid));
            if (state.phase == RVP_VirtualFlightPhase.VIRTUAL_CRUISE) {
                // 调用本项目目标点解析器和虚拟状态更新入口；目标实体仍加载时滚动刷新位置/速度，
                // 查询只读取已经加载的实体；固定 GPS 准入本身不因此扩展为动态目标制导。
                refreshLiveTargetMotion(ref, state);
                // 巡航态调用一次实体飞行计算；失败时 integrateOneTick 已负责终止记录。
                if (!integrateOneTick(ref, data)) continue;
                double horizontalSpeed = Math.sqrt(state.trajectory().velocity().x * state.trajectory().velocity().x
                        + state.trajectory().velocity().z * state.trajectory().velocity().z);
                double trigger = Math.max(data.getVirtualMidcourseData().getRestoreTargetDistance(),
                        horizontalSpeed * data.getVirtualMidcourseData().getRestoreLeadTick());
                if (state.trajectory().position().distanceTo(state.snapshot.targetPosition()) <= trigger) {
                    restoreCandidates.add(ref);
                }
            } else {
                // 等待恢复时位置暂停；按静止目标更新命中历史，不能永久保留最后飞行刻的受击盒偏移。
                state.trajectoryEntity.setOldPosAndRot();
            }
        }

        // 第二步：按请求时间/UUID 稳定排序，并受每 Tick 进入恢复阶段预算限制。
        restoreCandidates.sort(STATE_ORDER);
        for (int i = 0; i < Math.min(MAX_RESTORE_ENTRIES_PER_TICK, restoreCandidates.size()); i++) {
            StateRef ref = restoreCandidates.get(i);
            ref.state.phase = RVP_VirtualFlightPhase.RESTORE_REQUESTED;
            // 本刻末推送暂停事实，不能等到下一个双数刻才让画面停下。
            RVP_VirtualMissileVisualSync.requestSync(ref.level);
            ref.state.restoreRequestedGameTime = ref.level.getGameTime();
            ref.data.setDirty();
            RVP_VirtualMissileDebug.noteRestoreRequested();
            RVP_VirtualMissileDebug.lifecycle(ref.state, RVP_VirtualFlightReason.RESTORE_DISTANCE_REACHED, "");
        }

        List<StateRef> waiting = refs.stream()
                .filter(ref -> ref.data.contains(ref.state.flightUuid))
                .filter(ref -> ref.state.phase != RVP_VirtualFlightPhase.VIRTUAL_CRUISE)
                .sorted(STATE_ORDER).toList();
        // 第三步：先刷新已有租约，再用轮转算法分配本 Tick 新 Ticket 预算。
        allocateAndRefreshTickets(waiting);
        // 第四步：Ticket ready 后执行 UUID 检查、安全点搜索和实体重建。
        for (StateRef ref : waiting) processRestoreWaiting(ref, server);

        for (StateRef ref : refs) {
            if (ref.data.contains(ref.state.flightUuid) && ref.level.getGameTime() % 20 == 0) ref.data.setDirty();
        }
        RVP_VirtualMissileDebug.noteManagerNanos(System.nanoTime() - managerStart);
        RVP_VirtualMissileDebug.summarizeIfDue(server);
    }

    /** 服务器停止后清理仅运行时的计数与轮转游标；SavedData 由原版存档流程保存。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        fairnessCursor = 0;
    }

    /** 在不强制加载目标区块的前提下刷新虚拟弹的最后已知目标运动。 */
    private static void refreshLiveTargetMotion(StateRef ref, RVP_VirtualMissileState state) {
        Entity target = resolveEntity(ref.level, state.snapshot.targetEntityUuid());
        if (target == null || !target.isAlive()) {
            return;
        }
        Vec3 targetPosition = RVP_AimPointResolver.resolveLargestObbCenter(target);
        if (targetPosition == null) {
            return;
        }
        state.updateTargetMotion(targetPosition, target.getDeltaMovement());
        if (ref.level.getGameTime() % 20L == 0L) {
            // 调用本项目 SavedData 脏标记，按既有 20 Tick 持久化节奏保存滚动目标快照。
            ref.data.setDirty();
        }
    }

    /**
     * 推进一枚巡航态记录的一个逻辑 Tick，并处理寿命、版本和数值异常。
     *
     * @return 状态仍可继续参与本 Tick 后续恢复判定时为 {@code true}
     */
    private static boolean integrateOneTick(StateRef ref, RVP_WeaponData data) {
        RVP_VirtualMissileState state = ref.state;
        long start = System.nanoTime();
        RVP_VirtualMissileDebug.Vec3Before before = new RVP_VirtualMissileDebug.Vec3Before(
                state.trajectory().position(), state.trajectory().velocity());
        // 调用实体运动适配器：计算对象不加入世界，只执行与真实实体相同的制导和运动方法。
        RVP_VirtualTrajectoryResult result = RVP_EntityTrajectoryIntegrator.step(ref.level, state, data,
                resolveVehicle(ref.level, state.shooterVehicleUuid), resolveLivingEntity(ref.level, state.ownerUuid));
        RVP_VirtualMissileDebug.noteIntegrationNanos(System.nanoTime() - start);
        // 调用实体运动日志出口，快照已由实际执行链写回，不能再按虚拟规则重置 GPS/发动机状态。
        RVP_VirtualMissileDebug.trajectoryTick(state, before, data, result);
        state.virtualFlightTicks++;
        state.lastUpdatedGameTime = ref.level.getGameTime();
        if (state.trajectory().remainingLife() < 0) {
            terminate(ref, RVP_VirtualFlightReason.LIFE_EXPIRED, "");
            return false;
        }
        if (result.invalid() || !state.isStructurallyValid()) {
            RVP_VirtualMissileDebug.noteInvalid();
            terminate(ref, RVP_VirtualFlightReason.INVALID_STATE, "non-finite or malformed integration result");
            return false;
        }
        int maxTicks = Math.min(data.getVirtualMidcourseData().getMaxVirtualFlightTick(),
                Math.max(state.trajectory().remainingLife() + state.virtualFlightTicks + 1, 1));
        if (state.virtualFlightTicks >= maxTicks) {
            terminate(ref, RVP_VirtualFlightReason.MAX_VIRTUAL_TICKS, "");
            return false;
        }
        return true;
    }

    /**
     * 刷新等待队列已有 Ticket，并在全局上限内公平授予新 Ticket。
     *
     * <p>调用 {@link #fairAllocationOrder(int[], int, int)} 只计算索引顺序；真正改变
     * ChunkMap 状态的调用集中在 {@link #refreshTicket(ServerLevel, RVP_VirtualMissileState, ChunkPos)}。</p>
     */
    private static void allocateAndRefreshTickets(List<StateRef> waiting) {
        if (waiting.isEmpty()) return;
        // 已批准区块不占“新增”预算，但必须逐 Tick 刷新以维持 40 Tick 租约。
        for (StateRef ref : waiting) {
            for (long packed : ref.state.grantedRestoreChunks) refreshTicket(ref.level, ref.state, new ChunkPos(packed));
        }
        int start = Math.floorMod(fairnessCursor, waiting.size());
        int[] missing = new int[waiting.size()];
        for (int i = 0; i < waiting.size(); i++) {
            StateRef ref = waiting.get(i);
            missing[i] = (int) ref.state.desiredRestoreChunks(ref.config().getRestoreTicketRadius()).stream()
                    .filter(chunk -> !ref.state.grantedRestoreChunks.contains(chunk.toLong())).count();
        }
        // 纯规划器返回本 Tick 应获得一个新区块名额的记录索引序列。
        List<Integer> order = fairAllocationOrder(missing, MAX_NEW_RESTORE_CHUNKS_PER_TICK, start);
        for (int index : order) {
            StateRef ref = waiting.get(index);
            Optional<ChunkPos> next = ref.state.desiredRestoreChunks(ref.config().getRestoreTicketRadius()).stream()
                    .filter(chunk -> !ref.state.grantedRestoreChunks.contains(chunk.toLong())).findFirst();
            if (next.isEmpty()) continue;
            ChunkPos chunk = next.get();
            ref.state.grantedRestoreChunks.add(chunk.toLong());
            // 唯一的新增 Ticket 写入点；不会同步取得或生成 Chunk。
            refreshTicket(ref.level, ref.state, chunk);
            // 等待阶段变化本刻末提交视觉快照，后续位置和燃烧计数保持暂停。
            if (ref.state.phase != RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS)
                RVP_VirtualMissileVisualSync.requestSync(ref.level);
            ref.state.phase = RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS;
            ref.data.setDirty();
        }
        int granted = order.size();
        fairnessCursor = (start + Math.max(granted, 1)) % waiting.size();
        RVP_VirtualMissileDebug.noteTicketsGranted(granted);
    }

    /**
     * 计算公平的 Ticket 名额顺序，不访问服务器或修改输入数组。
     *
     * @param missingByState 每条等待记录尚缺的区块数
     * @param budget 本 Tick 可发放的最大名额数
     * @param startCursor 本轮从哪个状态索引开始
     * @return 状态索引序列；同一轮每条记录最多出现一次，仍有预算时再进入下一轮
     */
    static List<Integer> fairAllocationOrder(int[] missingByState, int budget, int startCursor) {
        List<Integer> order = new ArrayList<>();
        if (missingByState == null || missingByState.length == 0 || budget <= 0) return order;
        int[] remaining = missingByState.clone();
        int start = Math.floorMod(startCursor, remaining.length);
        boolean progressed = true;
        while (order.size() < budget && progressed) {
            progressed = false;
            for (int offset = 0; offset < remaining.length && order.size() < budget; offset++) {
                int index = (start + offset) % remaining.length;
                if (remaining[index] <= 0) continue;
                remaining[index]--;
                order.add(index);
                progressed = true;
            }
        }
        return order;
    }

    /**
     * 处理单枚等待恢复的记录：超时 → ready → 重试冷却 → UUID 冲突 → 实体恢复。
     */
    private static void processRestoreWaiting(StateRef ref, MinecraftServer server) {
        RVP_VirtualMissileState state = ref.state;
        state.restoreWaitTicks++;
        state.lastUpdatedGameTime = ref.level.getGameTime();
        if (state.restoreWaitTicks >= ref.config().getRestoreWaitTimeoutTick()) {
            RVP_VirtualMissileDebug.noteTimedOut();
            terminate(ref, RVP_VirtualFlightReason.RESTORE_TIMEOUT, "");
            return;
        }
        // 调用无副作用查询确认恢复半径及首 Tick 终点都已 entity-ticking。
        if (!restoreAreaReady(ref)) return;
        if (ref.level.getGameTime() < state.nextRestoreAttemptGameTime) return;
        // addFreshEntity 前跨维度检查 UUID，避免覆盖已恢复或意外残留的实体。
        if (findLiveEntityByUuid(server, state.flightUuid) != null) {
            retryOrTerminate(ref, RVP_VirtualFlightReason.UUID_CONFLICT, "live entity already owns UUID");
            return;
        }
        // restoreEntity 内部依次完成配置解析、快照写回、安全点搜索和加入世界。
        if (restoreEntity(ref)) {
            RVP_VirtualMissileDebug.noteRestored();
            RVP_VirtualMissileDebug.lifecycle(state, RVP_VirtualFlightReason.VIRTUAL_TO_REAL, "");
            ref.data.remove(state.flightUuid);
            // 只读实际已入世真实弹的最终位置，本刻末合并恢复交接集合。
            RVP_VirtualMissileVisualSync.requestSync(ref.level);
            // 调用同步服务，明确交接回真实实体，不能被客户端当成拦截销毁。
            RVP_VirtualMissileTargetSync.syncLevel(ref.level);
        } else {
            retryOrTerminate(ref, RVP_VirtualFlightReason.RESTORE_ADD_REJECTED, "addFreshEntity or safe position rejected");
        }
    }

    /** 记录一次恢复失败；未达上限则安排冷却重试，否则安全删除虚拟记录。 */
    private static void retryOrTerminate(StateRef ref, RVP_VirtualFlightReason reason, String detail) {
        ref.state.restoreAttemptCount++;
        RVP_VirtualMissileDebug.noteRestoreFailed();
        RVP_VirtualMissileDebug.lifecycle(ref.state, reason, detail);
        if (ref.state.restoreAttemptCount >= MAX_RESTORE_ATTEMPTS) {
            terminate(ref, reason, "retry limit reached; " + detail);
        } else {
            ref.state.nextRestoreAttemptGameTime = ref.level.getGameTime() + RESTORE_RETRY_INTERVAL_TICKS;
            ref.data.setDirty();
        }
    }

    /**
     * 从完整快照重建 RVP 类型化导弹并交回正常实体 Tick。
     *
     * @return 找到安全位置且 {@code addFreshEntity} 成功时为 {@code true}
     */
    private static boolean restoreEntity(StateRef ref) {
        RVP_VirtualMissileState state = ref.state;
        // 恢复时再次解析武器，避免使用保存前已被热重载删除的配置对象。
        RVP_WeaponData data = resolveWeapon(state.weaponId);
        if (data == null) return false;
        LivingEntity owner = resolveLivingEntity(ref.level, state.ownerUuid);
        AbstractVehicle shooterVehicle = resolveVehicle(ref.level, state.shooterVehicleUuid);
        // 调用虚拟计算同款恢复入口，完整保留实际机头姿态、当前速率和 PRESET 参考点。
        RVP_MissileEntity missile = RVP_EntityTrajectoryIntegrator.createEntity(ref.level, state, data, shooterVehicle, owner);
        // Chunk 已 ready 后才进行有限碰撞查询；该调用不会触发区块生成。
        Vec3 safePosition = findSafeRestorePosition(ref.level, missile, state.trajectory().position(),
                state.trajectory().velocity());
        if (safePosition == null) return false;
        missile.setPos(safePosition);
        Entity target = resolveEntity(ref.level, state.snapshot.targetEntityUuid());
        if (target != null && target.isAlive()) missile.setTargetEntity(target);
        // 只有全部状态和稳定 UUID 写回后才将实体加入世界。
        if (!ref.level.addFreshEntity(missile)) return false;
        if (state.trajectoryEntity != null) {
            // 调用现有引用交接，恢复后雷达和已在途拦截弹继续追同一 UUID/会话 ID。
            RVP_VirtualMissileTargets.replaceReferences(ref.level, state.trajectoryEntity, missile);
        }
        // 首次路径请求把控制权交回常规 5 Tick 滚动区块保护。
        missile.primeDynamicChunkPath();
        return true;
    }

    /**
     * 在已 ready 区块内搜索恢复碰撞安全点；先原位，再交替向上和沿速度反向搜索。
     *
     * @return 最多 32 步内找到的无碰撞位置；否则返回 {@code null}
     */
    @Nullable
    private static Vec3 findSafeRestorePosition(ServerLevel level, RVP_MissileEntity missile,
                                                 Vec3 requested, Vec3 velocity) {
        Vec3 backward = velocity.lengthSqr() > 1.0E-8 ? velocity.normalize().reverse() : Vec3.ZERO;
        for (int step = 0; step <= 32; step++) {
            Vec3 candidate = step == 0 ? requested
                    : (step % 2 == 0 ? requested.add(0, step / 2.0, 0) : requested.add(backward.scale((step + 1) / 2.0)));
            BlockPos probe = BlockPos.containing(candidate);
            if (!level.hasChunkAt(probe) || !level.isPositionEntityTicking(probe)) continue;
            missile.setPos(candidate);
            if (level.noCollision(missile, missile.getBoundingBox())) return candidate;
        }
        return null;
    }

    /** 检查所有已规划恢复区块、当前位置和首 Tick 终点是否 loaded + entity-ticking。 */
    private static boolean restoreAreaReady(StateRef ref) {
        Set<ChunkPos> desired = ref.state.desiredRestoreChunks(ref.config().getRestoreTicketRadius());
        if (desired.stream().anyMatch(chunk -> !ref.state.grantedRestoreChunks.contains(chunk.toLong()))) return false;
        Vec3 position = ref.state.trajectory().position();
        Vec3 endpoint = position.add(ref.state.trajectory().velocity());
        if (!ready(ref.level, position) || !ready(ref.level, endpoint)) return false;
        for (ChunkPos chunk : desired) {
            BlockPos probe = chunk.getMiddleBlockPosition((int) position.y);
            if (!ref.level.hasChunkAt(probe) || !ref.level.isPositionEntityTicking(probe)) return false;
        }
        return true;
    }

    /** 对单个世界坐标执行无同步加载副作用的 ready 查询。 */
    private static boolean ready(ServerLevel level, Vec3 position) {
        BlockPos probe = BlockPos.containing(position);
        return level.hasChunkAt(probe) && level.isPositionEntityTicking(probe);
    }

    /** 添加或刷新一条 UUID 恢复 Ticket；调用者负责预算和目标区块去重。 */
    private static void refreshTicket(ServerLevel level, RVP_VirtualMissileState state, ChunkPos chunk) {
        level.getChunkSource().addRegionTicket(RESTORE_TICKET, chunk, RESTORE_TICKET_LEVEL, state.flightUuid);
    }

    /**
     * 从服务器所有维度收集 SavedData，并就地清理维度错配、非法状态和重复 UUID。
     */
    private static List<StateRef> collectAndValidate(MinecraftServer server) {
        List<StateRef> refs = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            // 通过 SavedData.get 进入持久化加载链；不会加载任何导弹沿途区块。
            RVP_VirtualMissileSavedData data = RVP_VirtualMissileSavedData.get(level);
            List<RVP_VirtualMissileState> copy = new ArrayList<>(data.states());
            for (RVP_VirtualMissileState state : copy) {
                if (!state.dimension.equals(level.dimension().location()) || !state.isStructurallyValid()) {
                    RVP_VirtualMissileDebug.noteInvalid();
                    terminate(new StateRef(level, data, state), RVP_VirtualFlightReason.INVALID_STATE,
                            "dimension or codec validation failed");
                } else if (!seen.add(state.flightUuid)) {
                    terminate(new StateRef(level, data, state), RVP_VirtualFlightReason.DUPLICATE_SAVED_UUID,
                            "duplicate UUID in another dimension");
                } else {
                    refs.add(new StateRef(level, data, state));
                }
            }
        }
        return refs;
    }

    /** 输出终止原因并从所属维度 SavedData 删除记录。 */
    private static void terminate(StateRef ref, RVP_VirtualFlightReason reason, String detail) {
        RVP_VirtualMissileDebug.lifecycle(ref.state, reason, detail);
        for (long chunk : ref.state.grantedRestoreChunks) {
            // 终止后立即归还恢复区块票据，拦截成功不能再触发末段重建。
            ref.level.getChunkSource().removeRegionTicket(
                    RESTORE_TICKET, new ChunkPos(chunk), RESTORE_TICKET_LEVEL, ref.state.flightUuid);
        }
        ref.state.grantedRestoreChunks.clear();
        ref.data.remove(ref.state.flightUuid);
        // 终止/拦截后的视觉撤销本刻提交，不改原有伤害和区块票据结算顺序。
        RVP_VirtualMissileVisualSync.requestSync(ref.level);
    }

    /** 当前维度全部存活虚拟目标；巡航和等待恢复均保留，不加载区块或额外推进飞行。 */
    public static List<RVP_MissileEntity> targets(ServerLevel level) {
        List<RVP_MissileEntity> targets = new ArrayList<>();
        // 调用维度容器读取已初始化对象，查询不重新创建或积分任何弹体。
        for (RVP_VirtualMissileState state : RVP_VirtualMissileSavedData.get(level).states()) {
            if (state.dimension.equals(level.dimension().location())
                    && state.trajectoryEntity != null && state.trajectoryEntity.isAlive()) {
                targets.add(state.trajectoryEntity);
            }
        }
        return targets;
    }

    /** 仅计算对象本身的移除可删除记录；同 UUID 的旧真实实体或恢复实体不能误删。 */
    public static void removeTarget(org.ywzj.rvp.entity.projectile.RVP_BaseBullet target) {
        if (!(target.level() instanceof ServerLevel level)) return;
        // 调用权威容器的 UUID 查询，并额外校验对象引用，防转换与重建期间串删。
        RVP_VirtualMissileSavedData data = RVP_VirtualMissileSavedData.get(level);
        RVP_VirtualMissileState state = data.get(target.getUUID());
        if (state == null || state.trajectoryEntity != target) return;
        // 调用引用清理，已被拦截的目标立即退出雷达接触和现有火控锁定。
        RVP_VirtualMissileTargets.replaceReferences(level, target, null);
        terminate(new StateRef(level, data, state), RVP_VirtualFlightReason.INTERCEPTED, "virtual target removed");
        // 调用完整集合同步，客户端及时清理已被拦截的接触与硬锁。
        RVP_VirtualMissileTargetSync.syncLevel(level);
    }

    /** @return 任一维度 SavedData 是否已持有指定稳定 UUID。 */
    private static boolean containsState(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) if (RVP_VirtualMissileSavedData.get(level).contains(uuid)) return true;
        return false;
    }

    /** @return 所有已加载维度当前持久化的虚拟导弹总数。 */
    public static int activeCount(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) count += RVP_VirtualMissileSavedData.get(level).size();
        return count;
    }

    /** 从当前武器索引解析当前 schema 的 RVP 武器数据。 */
    @Nullable
    private static RVP_WeaponData resolveWeapon(ResourceLocation id) {
        return CommonAssetsManager.vehicleWeaponManager().getIndex(id)
                .map(index -> index.data() instanceof RVP_WeaponData data ? data : null).orElse(null);
    }

    /** 跨所有已加载维度查找同 UUID 的真实实体，用于恢复冲突保护。 */
    @Nullable private static Entity findLiveEntityByUuid(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }
    /** 只在恢复维度内解析可选实体 UUID，不为查询目标加载其他维度或区块。 */
    @Nullable private static Entity resolveEntity(ServerLevel level, @Nullable UUID uuid) {
        return uuid == null ? null : level.getEntity(uuid);
    }
    /** 在恢复维度内解析仍存活的发射者。 */
    @Nullable private static LivingEntity resolveLivingEntity(ServerLevel level, @Nullable UUID uuid) {
        Entity entity = resolveEntity(level, uuid);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }
    /** 在恢复维度内解析仍存活的发射载具。 */
    @Nullable private static AbstractVehicle resolveVehicle(ServerLevel level, @Nullable UUID uuid) {
        Entity entity = resolveEntity(level, uuid);
        return entity instanceof AbstractVehicle vehicle && vehicle.isAlive() ? vehicle : null;
    }

    /** 恢复队列稳定顺序：优先请求时间，其次 UUID，保证重启后排序可重复。 */
    private static final Comparator<StateRef> STATE_ORDER = Comparator
            .comparingLong((StateRef ref) -> ref.state.restoreRequestedGameTime == 0
                    ? ref.state.enteredGameTime : ref.state.restoreRequestedGameTime)
            .thenComparing(ref -> ref.state.flightUuid.toString());

    /** 把维度、所属 SavedData 和状态绑定，避免辅助方法再次全服查找容器。 */
    private record StateRef(ServerLevel level, RVP_VirtualMissileSavedData data,
                            RVP_VirtualMissileState state) {
        /** 动态解析当前武器的虚拟中段配置；武器缺失时返回安全默认关闭配置。 */
        RVP_VirtualMidcourseData config() {
            RVP_WeaponData weapon = resolveWeapon(state.weaponId);
            return weapon == null ? new RVP_VirtualMidcourseData() : weapon.getVirtualMidcourseData();
        }
    }
}
