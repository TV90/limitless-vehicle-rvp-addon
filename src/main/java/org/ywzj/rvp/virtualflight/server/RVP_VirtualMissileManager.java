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
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightReason;
import org.ywzj.rvp.virtualflight.trajectory.RVP_RvpTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualGuidanceInput;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryIntegrator;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryParameters;
import org.ywzj.rvp.virtualflight.trajectory.RVP_VirtualTrajectoryResult;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
    /** 当前注册的纯弹道积分器；阶段 B 默认仍为 {@code rvp_current}。 */
    private static RVP_VirtualTrajectoryIntegrator integrator = new RVP_RvpTrajectoryIntegrator();
    /** 最近一次 ServerTick 收集到的记录数，用于阻止带活动状态的热切换。 */
    private static int lastKnownActiveCount;
    /** 跨 Tick 保留的恢复预算轮转起点，避免列表前部长期优先。 */
    private static int fairnessCursor;

    private RVP_VirtualMissileManager() {}

    /**
     * 安装新的纯轨迹积分器。
     *
     * @param replacement 新实现，不得为 {@code null}
     * @throws IllegalStateException 当前存在活动虚拟记录时抛出，防止在途状态静默换算法
     */
    public static void installIntegrator(RVP_VirtualTrajectoryIntegrator replacement) {
        if (lastKnownActiveCount > 0) throw new IllegalStateException("Cannot replace integrator while virtual missiles are active");
        integrator = Objects.requireNonNull(replacement, "replacement");
    }

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
                missile.getId(), missile.getColdLaunchTimeTick(), gameTime, gameTime,
                integrator.implementationId(), integrator.implementationVersion(),
                RVP_VirtualFlightPhase.VIRTUAL_CRUISE, 0, 0, 0, 0);
        // 通过维度 DataStorage 取得权威容器；add 成功会立即 mark dirty。
        RVP_VirtualMissileSavedData savedData = RVP_VirtualMissileSavedData.get(level);
        if (!state.isStructurallyValid() || !savedData.add(state)) return false;

        RVP_VirtualMissileDebug.noteEntered();
        RVP_VirtualMissileDebug.lifecycle(state, RVP_VirtualFlightReason.REAL_TO_VIRTUAL,
                "eligibility=" + eligibility);
        // 先标记专用移除原因，再调用普通 discard，让生命周期日志能识别转换语义。
        missile.beginVirtualMidcourseRemoval();
        missile.discard();
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
        lastKnownActiveCount = refs.size();
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
            if (state.phase == RVP_VirtualFlightPhase.VIRTUAL_CRUISE) {
                // 巡航态只调用一次纯积分；失败时 integrateOneTick 已负责终止记录。
                if (!integrateOneTick(ref, data)) continue;
                double horizontalSpeed = Math.sqrt(state.trajectory().velocity().x * state.trajectory().velocity().x
                        + state.trajectory().velocity().z * state.trajectory().velocity().z);
                double trigger = Math.max(data.getVirtualMidcourseData().getRestoreTargetDistance(),
                        horizontalSpeed * data.getVirtualMidcourseData().getRestoreLeadTick());
                if (state.trajectory().position().distanceTo(state.snapshot.targetPosition()) <= trigger) {
                    restoreCandidates.add(ref);
                }
            }
        }

        // 第二步：按请求时间/UUID 稳定排序，并受每 Tick 进入恢复阶段预算限制。
        restoreCandidates.sort(STATE_ORDER);
        for (int i = 0; i < Math.min(MAX_RESTORE_ENTRIES_PER_TICK, restoreCandidates.size()); i++) {
            StateRef ref = restoreCandidates.get(i);
            ref.state.phase = RVP_VirtualFlightPhase.RESTORE_REQUESTED;
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
        lastKnownActiveCount = 0;
        fairnessCursor = 0;
    }

    /**
     * 推进一枚巡航态记录的一个逻辑 Tick，并处理寿命、版本和数值异常。
     *
     * @return 状态仍可继续参与本 Tick 后续恢复判定时为 {@code true}
     */
    private static boolean integrateOneTick(StateRef ref, RVP_WeaponData data) {
        RVP_VirtualMissileState state = ref.state;
        if (!state.trajectoryImplementationId.equals(integrator.implementationId())
                || state.trajectoryStateVersion != integrator.implementationVersion()) {
            terminate(ref, RVP_VirtualFlightReason.INCOMPATIBLE_INTEGRATOR, "loaded state implementation mismatch");
            return false;
        }
        long start = System.nanoTime();
        // 从当前武器数据生成纯参数，随后只把不可变状态/输入交给积分器。
        RVP_VirtualTrajectoryParameters parameters = RVP_VirtualTrajectoryParameters.from(
                data, state.coldLaunchTimeTick, state.trajectory().position().y);
        RVP_VirtualMissileDebug.Vec3Before before = new RVP_VirtualMissileDebug.Vec3Before(
                state.trajectory().position(), state.trajectory().velocity());
        // 核心调用：积分器不接触 Level、Entity、Ticket 或任何同步加载 API。
        RVP_VirtualTrajectoryResult result = integrator.step(state.trajectory(),
                new RVP_VirtualGuidanceInput(state.snapshot.targetPosition()), parameters);
        RVP_VirtualMissileDebug.noteIntegrationNanos(System.nanoTime() - start);
        // 将不可变积分结果替换回完整快照，同时保留制导/雷达/GPS/Top Attack 状态。
        state.updateTrajectory(result.state());
        RVP_VirtualMissileDebug.trajectoryTick(state, before, parameters, result);
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
        RVP_MissileEntity missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), ref.level, state.weaponId);
        RVP_BaseBullet.AimRot aim = new RVP_BaseBullet.AimRot(state.trajectory().xRot(), state.trajectory().yRot());
        // 先走标准初始化建立同步字段与运行组件，再用专用快照覆盖在途状态。
        missile.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, shooterVehicle, owner,
                state.trajectory().position(), aim, state.trajectory().velocity());
        missile.setUUID(state.flightUuid);
        missile.restoreFromVirtualMidcourseSnapshot(state.snapshot, state.launchPosition);
        // 快照恢复后再调用专用对齐入口，避免旧快照姿态覆盖虚拟段最终速度方向。
        missile.alignVirtualRestoredAttitudeToVelocity();
        // Chunk 已 ready 后才进行有限碰撞查询；该调用不会触发区块生成。
        Vec3 safePosition = findSafeRestorePosition(ref.level, missile, state.trajectory().position(),
                state.trajectory().velocity());
        if (safePosition == null) return false;
        missile.setPos(safePosition);
        Entity target = resolveEntity(ref.level, state.snapshot.targetEntityUuid());
        if (target != null && target.isAlive()) missile.setTargetEntity(target);
        // 只有全部状态和稳定 UUID 写回后才将实体加入世界。
        if (!ref.level.addFreshEntity(missile)) return false;
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
        ref.data.remove(ref.state.flightUuid);
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
