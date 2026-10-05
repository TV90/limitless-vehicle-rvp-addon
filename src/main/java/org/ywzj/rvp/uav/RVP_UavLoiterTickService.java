package org.ywzj.rvp.uav;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientLoiterState;
import org.ywzj.rvp.config.RVP_LoiterConfig;
import org.ywzj.rvp.config.RVP_LoiterConfigCache;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CLoiterStateSync;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterPhase;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.LoiterState;
import org.ywzj.rvp.uav.RVP_UavLoiterManager.MutableState;
import org.ywzj.rvp.uav.RVP_UavLoiterGuidance.GuidanceOutput;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无人机盘旋服务端 tick 处理器。遍历 active UAV，计算制导并写入 ControlUnit。
 * <p>非 Mixin，通过 Forge 事件总线挂载。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_UavLoiterTickService {

    private RVP_UavLoiterTickService() {}

    /** 目标高度的地形余量（格），与 {@link RVP_LoiterConfig#TERRAIN_CLEARANCE} 同源。 */
    private static final double TERRAIN_CLEARANCE = RVP_LoiterConfig.TERRAIN_CLEARANCE;
    /** 客户端同步间隔（tick）。 */
    private static final int SYNC_INTERVAL_TICKS = 10;
    /** 阶段超时（tick）。 */
    private static final int CLIMB_TIMEOUT_TICKS = 200;
    private static final int TRANSIT_TIMEOUT_TICKS = 1200;
    private static final int APPROACH_TIMEOUT_TICKS = 400;
    /** 实体暂时查不到（区块卸载/维度切换中）的宽限（tick）：宽限内保留盘旋状态，实体回来无缝续飞。 */
    private static final int ENTITY_MISSING_GRACE_TICKS = 100;

    /** 实体缺失宽限计数（key = UAV UUID）。 */
    private static final Map<UUID, Integer> ENTITY_MISSING_TICKS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        int tickCount = server.getTickCount();

        // 遍历所有盘旋状态
        for (Map.Entry<UUID, MutableState> entry : RVP_UavLoiterManager.getAllInternal().entrySet()) {
            UUID uavUuid = entry.getKey();
            MutableState state = entry.getValue();
            if (!state.active) {
                continue;
            }
            // 查找 UAV 实体：查不到不立即删状态（区块卸载/换维度是瞬时的），给宽限期防止
            // "飞远→卸载→盘旋静默消失"（2026-10-06 修复）；宽限内实体回来直接续飞
            AbstractVehicle uav = resolveVehicle(server, uavUuid);
            if (uav == null || !uav.isAlive() || uav.isRemoved()) {
                int missing = ENTITY_MISSING_TICKS.merge(uavUuid, 1, Integer::sum);
                if (missing > ENTITY_MISSING_GRACE_TICKS) {
                    ENTITY_MISSING_TICKS.remove(uavUuid);
                    RVP_UavLoiterManager.remove(uavUuid);
                }
                continue;
            }
            ENTITY_MISSING_TICKS.remove(uavUuid);
            // 盘旋激活时制导优先；玩家运动输入由 ControlUnitMixin 屏蔽，
            // 玩家仍可操作武器（functional 输入）和按 F 键退出盘旋
            // 获取配置
            RVP_LoiterConfig config = resolveConfig(uav);
            // 更新圆心（跟随母车模式）
            updateCenterIfFollowing(server, state);
            // 计算目标高度（圆心偏移与地形余量取最大）
            double targetAltitude = resolveTargetAltitude(uav, state, config);
            // 制导计算
            boolean isRotaryWing = uav instanceof RotaryWingVehicle;
            GuidanceOutput out = computeGuidance(uav, state, targetAltitude, isRotaryWing, tickCount, config);
            // 写入 ControlUnit
            applyControlUnit(uav, out);
            // 阶段切换
            updatePhase(state, out, tickCount);
            // UAV 自身当前与速度前探区块由 RVP 远距载具租约服务统一提交，避免盘旋服务重复加票。
        }
        ENTITY_MISSING_TICKS.keySet().removeIf(uuid -> RVP_UavLoiterManager.get(uuid) == null);

        // 定期同步盘旋状态到客户端
        if (server.getTickCount() % SYNC_INTERVAL_TICKS == 0) {
            syncLoiterStateToClients(server);
        }
    }

    /**
     * 关闭盘旋并清空 ControlUnit（2026-10-06 修复油门/航向锁存直飞）：
     * 旧版只置 active=false，最后一次制导输出（满油门/航向）被锁存，UAV 按旧指令永久直飞。
     * 所有盘旋关闭点（F 键/信号范围回收/实体移除）一律走本方法。
     */
    public static void stopLoiterAndResetControls(AbstractVehicle uav) {
        if (uav == null) {
            return;
        }
        RVP_UavLoiterManager.disable(uav.getUUID());
        uav.controlUnit.reset();
    }

    /** 同步活跃盘旋圆到所有客户端，供战术地图渲染。 */
    private static void syncLoiterStateToClients(MinecraftServer server) {
        List<RVP_ClientLoiterState.LoiterCircle> circles = new java.util.ArrayList<>();
        for (Map.Entry<UUID, MutableState> entry : RVP_UavLoiterManager.getAllInternal().entrySet()) {
            MutableState state = entry.getValue();
            if (!state.active) {
                continue;
            }
            // 查找 UAV 所在维度
            AbstractVehicle uav = resolveVehicle(server, entry.getKey());
            if (uav == null) {
                continue;
            }
            ResourceLocation dim = uav.level().dimension().location();
            circles.add(new RVP_ClientLoiterState.LoiterCircle(
                    dim, state.centerX, state.centerZ, state.radius, state.active, uav.getId()));
        }
        S2CLoiterStateSync msg = new S2CLoiterStateSync();
        msg.circles = circles;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RVP_Network.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), msg);
        }
    }

    /** 计算制导输出。 */
    private static GuidanceOutput computeGuidance(AbstractVehicle uav, MutableState state,
                                                   double targetAltitude, boolean isRotaryWing,
                                                   int tickCount, RVP_LoiterConfig config) {
        double uavX = uav.getX();
        double uavY = uav.getY();
        double uavZ = uav.getZ();
        float uavYaw = uav.getYRot();
        // 垂直速度（m/s）与空速（m/s、blocks/tick 水平分量）
        double climbRateMps = uav.getDeltaMovement().y * 20.0;
        double hSpeedMps = Math.sqrt(uav.getDeltaMovement().horizontalDistanceSqr()) * 20.0;
        double hSpeedBlocks = hSpeedMps / 20.0;

        double radius = state.radius;
        double solutionBank = 0;
        double solutionSpeed = 0;
        if (!isRotaryWing) {
            // 固定翼：解出满足升力约束的（实际半径， 坡度， 速度）——配置半径权威，
            // 升力不足（v_min² > gR·sinφ_cap）时才按坡度上限反算最小半径兜底
            double vMinMps = resolveFixedWingMinSpeedMps(uav);
            RVP_UavLoiterGuidance.FixedWingLoiterSolution solution =
                    RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(state.radius, vMinMps, hSpeedBlocks);
            radius = solution.actualRadius();
            solutionBank = solution.targetBankDeg();
            solutionSpeed = solution.targetSpeedMps();
            // 半径解算只依赖气动常量（v_min/配置半径），结果稳定——同步回 state 供战术地图圆环如实显示
            state.radius = radius;
        }

        return switch (state.phase) {
            case CLIMB -> RVP_UavLoiterGuidance.computeClimb(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    targetAltitude, climbRateMps, hSpeedMps, solutionSpeed, isRotaryWing, tickCount);
            case TRANSIT -> RVP_UavLoiterGuidance.computeTransit(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps, hSpeedMps, solutionSpeed, isRotaryWing, tickCount);
            case APPROACH -> RVP_UavLoiterGuidance.computeApproach(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps,
                    RVP_LoiterConfig.LOITER_DIRECTION, isRotaryWing, tickCount);
            case LOITER -> RVP_UavLoiterGuidance.computeLoiter(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps, isRotaryWing, tickCount,
                    uav.getZRot(), hSpeedMps, solutionBank, solutionSpeed);
        };
    }

    /**
     * 固定翼最小平飞速度（m/s）：升力（=阻力×升阻比，随速度²缩放）恰好等于重力的速度，
     * v_min = sqrt(G·m/(k_min·liftToDrag))。气动字段/质量不可得时返回 0（解算器走几何回退）。
     */
    private static double resolveFixedWingMinSpeedMps(AbstractVehicle uav) {
        if (!(uav instanceof FixedWingVehicle fw)
                || fw.liftToDragK <= 0 || fw.airDragKMin <= 0
                || uav.physicsEngine == null || uav.physicsEngine.physicsInfo == null
                || uav.physicsEngine.physicsInfo.mass <= 0) {
            return 0;
        }
        double vMinBlocks = Math.sqrt(
                org.ywzj.vehicle.vehicle.PhysicsEngine.G * uav.physicsEngine.physicsInfo.mass
                        / (fw.airDragKMin * fw.liftToDragK));
        return vMinBlocks * 20.0;
    }

    /** 写入 ControlUnit。 */
    private static void applyControlUnit(AbstractVehicle uav, GuidanceOutput out) {
        uav.controlUnit.reset();
        uav.controlUnit.forward = out.forward();
        uav.controlUnit.backward = out.backward();
        uav.controlUnit.up = out.up();
        uav.controlUnit.down = out.down();
        uav.controlUnit.left = out.left();
        uav.controlUnit.right = out.right();
        if (out.useAnalogYaw()) {
            // 旋翼机：设置目标偏航角，物理引擎自动平滑追踪（yawAimControl 无 driver 检查，无人可用）
            uav.controlUnit.yRot = out.targetYRot();
            uav.controlUnit.yRotKeep = false;
        } else {
            // 固定翼（2026-10-06 重构）：布尔杆量闭环——leftYaw/rightYaw 偏航强对齐 + left/right
            // 坡度脉冲，无人驾驶时全部有效（本体 getDriver()==null 只覆写 xRot/yRot 浮点）。
            // yRot 仍写期望航向：无人时被本体覆写无副作用；玩家在机时（不覆写）供瞄准协调逻辑使用
            uav.controlUnit.leftYaw = out.leftYaw();
            uav.controlUnit.rightYaw = out.rightYaw();
            uav.controlUnit.yRot = out.targetYRot();
            uav.controlUnit.yRotKeep = false;
        }
    }

    /** 阶段切换 + 超时降级。 */
    private static void updatePhase(MutableState state, GuidanceOutput out, int tickCount) {
        LoiterPhase current = state.phase;
        LoiterPhase next = out.nextPhase();
        if (next != current) {
            state.phase = next;
            state.phaseTickCounter = 0;
            state.phaseTimeout = resolvePhaseTimeout(next);
        } else {
            state.phaseTickCounter++;
            // 超时降级
            if (state.phaseTickCounter > state.phaseTimeout) {
                state.phase = nextPhase(current);
                state.phaseTickCounter = 0;
                state.phaseTimeout = resolvePhaseTimeout(state.phase);
            }
        }
    }

    /** 更新圆心（跟随母车模式）。 */
    private static void updateCenterIfFollowing(MinecraftServer server, MutableState state) {
        if (state.followParentUuid == null) {
            return;
        }
        AbstractVehicle parent = resolveVehicle(server, state.followParentUuid);
        if (parent != null && parent.isAlive() && !parent.isRemoved()) {
            state.centerX = parent.getX();
            state.centerY = parent.getY();
            state.centerZ = parent.getZ();
        }
        // 母车不存在时保持最后已知位置
    }

    /** 计算目标高度（圆心+偏移 与 地表+余量 取最大；min_safe_altitude 已随参数精简删除）。 */
    private static double resolveTargetAltitude(AbstractVehicle uav, MutableState state, RVP_LoiterConfig config) {
        double altFromCenter = state.centerY + config.loiterAltitudeOffset();
        // 地形高度（当前正下方）
        double terrainY = sampleTerrainAt(uav.level(), uav.getX(), uav.getZ());
        return Math.max(altFromCenter, terrainY + TERRAIN_CLEARANCE);
    }

    /** 采样正下方地形高度。 */
    private static double sampleTerrainAt(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) x, (int) z);
    }

    /** 阶段超时阈值。 */
    private static int resolvePhaseTimeout(LoiterPhase phase) {
        return switch (phase) {
            case CLIMB -> CLIMB_TIMEOUT_TICKS;
            case TRANSIT -> TRANSIT_TIMEOUT_TICKS;
            case APPROACH -> APPROACH_TIMEOUT_TICKS;
            case LOITER -> Integer.MAX_VALUE; // 盘旋不超时
        };
    }

    /** 下一阶段（超时降级用）。 */
    private static LoiterPhase nextPhase(LoiterPhase current) {
        return switch (current) {
            case CLIMB -> LoiterPhase.TRANSIT;
            case TRANSIT -> LoiterPhase.LOITER; // 航渡超时直接进盘旋
            case APPROACH -> LoiterPhase.LOITER;
            case LOITER -> LoiterPhase.LOITER;
        };
    }

    /** 通过 UUID 在服务端查找载具。 */
    private static AbstractVehicle resolveVehicle(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof AbstractVehicle vehicle) {
                return vehicle;
            }
        }
        return null;
    }

    /** 获取盘旋配置。优先查母车配置（可部署 UAV 场景），其次查载具自身配置（AC130 等通用场景）。 */
    private static RVP_LoiterConfig resolveConfig(AbstractVehicle uav) {
        UUID parentUuid = RVP_LinkedUavStateTable.getLinkedParentVehicleUuid(uav);
        if (parentUuid != null) {
            AbstractVehicle parent = resolveVehicle(uav.getServer(), parentUuid);
            if (parent != null) {
                RVP_LoiterConfig parentConfig = RVP_LoiterConfigCache.get(parent.getVehicleId());
                if (parentConfig.isConfigured()) {
                    return parentConfig;
                }
            }
        }
        // 回退到载具自身配置（AC130、空中炮艇等通用盘旋场景）
        return RVP_LoiterConfigCache.get(uav.getVehicleId());
    }
}
