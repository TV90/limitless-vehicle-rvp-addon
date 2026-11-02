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

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        MinecraftServer server = event.getServer();
        int tickCount = server.getTickCount();

        // 遍历所有盘旋状态
        for (Map.Entry<UUID, MutableState> entry : RVP_UavLoiterManager.getAllInternal().entrySet()) {
            UUID uavUuid = entry.getKey();
            MutableState state = entry.getValue();
            if (!state.active || state.signalPaused) {
                continue;
            }
            // 查找 UAV 实体：EntityLeaveLevelEvent 会仅清理 runtime；盘旋计划保存在实体 persistentData。
            AbstractVehicle uav = resolveVehicle(server, uavUuid);
            if (uav == null || !uav.isAlive() || uav.isRemoved()) {
                continue;
            }
            if (uav.isDestroyed() || !RVP_UavLoiterManager.supportsLoiter(uav)) {
                stopLoiterAndResetControls(uav);
                RVP_UavLoiterManager.persist(uav);
                continue;
            }
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
            RVP_UavLoiterManager.persist(uav);
            // UAV 自身当前与速度前探区块由 RVP 远距载具租约服务统一提交，避免盘旋服务重复加票。
        }
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
        RVP_UavLoiterManager.persist(uav);
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
                    dim, state.centerX, state.centerZ, state.effectiveRadius, state.active, uav.getId()));
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
        double airSpeedBlocks = uav.getDeltaMovement().length();

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
            state.effectiveRadius = radius;
            // requested radius 不被实际可行半径覆盖，避免高度/失速变化造成半径只增不减。
        }

        return switch (state.phase) {
            case CLIMB -> RVP_UavLoiterGuidance.computeClimb(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    targetAltitude, climbRateMps, hSpeedMps, solutionSpeed, isRotaryWing, tickCount,
                    uav.getZRot());
            case TRANSIT -> RVP_UavLoiterGuidance.computeTransit(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps, hSpeedMps, solutionSpeed, isRotaryWing, tickCount,
                    uav.getZRot());
            case APPROACH -> RVP_UavLoiterGuidance.computeApproach(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps,
                    RVP_LoiterConfig.LOITER_DIRECTION, isRotaryWing, tickCount, uav.getZRot(),
                    hSpeedMps, solutionSpeed);
            case LOITER -> RVP_UavLoiterGuidance.computeLoiter(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps, isRotaryWing, tickCount,
                    uav.getZRot(), hSpeedMps, solutionBank, solutionSpeed,
                    resolveAxisRatePerInput(uav, airSpeedBlocks, true),
                    resolveAxisRatePerInput(uav, airSpeedBlocks, false));
            case RECOVERY -> RVP_UavLoiterGuidance.computeRecovery(
                    uavX, uavY, uavZ, uavYaw, state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, climbRateMps, RVP_LoiterConfig.LOITER_DIRECTION,
                    tickCount, uav.getZRot(), hSpeedMps, solutionBank, solutionSpeed);
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
        double scaleAir = uav.getY() < 64.0 ? 1.0
                : Math.sqrt(Math.max(0.0, fw.ceiling - uav.getY()) / Math.max(1.0, fw.ceiling - 64.0));
        scaleAir = Math.max(0.001, scaleAir);
        // FixedWingVehicle.tickMove：f=v²·400·k，升力系数为 L/D·scaleAir 加迎角修正；
        // angleOfAttack 取温和正迎角，另留 12% 速度余量覆盖操纵阻力与数值耦合。
        double aoaDeg = Math.max(0.0, Math.min(5.0, fw.angleOfAttackMax));
        return RVP_UavLoiterGuidance.estimateFixedWingMinSpeedMps(
                uav.physicsEngine.physicsInfo.mass, fw.airDragKMin, fw.airDragKMax,
                fw.liftToDragK, scaleAir, aoaDeg, fw.angleOfAttackMax);
    }

    /** 本体 tickMove 每单位持续输入实际能提供的单 tick 旋转角。 */
    private static double resolveAxisRatePerInput(AbstractVehicle uav, double speedBlocksPerTick,
                                                  boolean pitchAxis) {
        if (!(uav instanceof FixedWingVehicle fw)) {
            return 0.0;
        }
        double scaleAir = uav.getY() < 64.0 ? 1.0
                : Math.sqrt(Math.max(0.0, fw.ceiling - uav.getY()) / Math.max(1.0, fw.ceiling - 64.0));
        scaleAir = Math.max(0.001, scaleAir);
        double rate = pitchAxis ? fw.xTurnRate : fw.yTurnRate;
        return Math.min(rate, scaleAir * speedBlocksPerTick * fw.turnRateBySpeed * rate);
    }

    /** 写入 ControlUnit。 */
    private static void applyControlUnit(AbstractVehicle uav, GuidanceOutput out) {
        boolean functionalUp = uav.controlUnit.functionalUp;
        boolean functionalDown = uav.controlUnit.functionalDown;
        boolean functionalLeft = uav.controlUnit.functionalLeft;
        boolean functionalRight = uav.controlUnit.functionalRight;
        uav.controlUnit.reset();
        uav.controlUnit.functionalUp = functionalUp;
        uav.controlUnit.functionalDown = functionalDown;
        uav.controlUnit.functionalLeft = functionalLeft;
        uav.controlUnit.functionalRight = functionalRight;
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
        } else if (uav instanceof FixedWingVehicle fixedWing) {
            // 每个轴都持续指定正向键并在 setter 预减 inputStep；tickMove 消费后得到目标杆量。
            // up/leftYaw/left 非空挡输入阻断本体自动俯仰/偏航/滚转控制；零yaw仍保留气动尾舵回正。
            float xStep = fixedWing.xRotInputStep;
            float yStep = fixedWing.yRotInputStep;
            float zStep = fixedWing.zRotInputStep;
            float pitchTarget = out.pitchInput();
            float rollTarget = out.rollInput();
            uav.controlUnit.up = true;
            uav.controlUnit.leftYaw = true;
            uav.controlUnit.left = true;
            fixedWing.setPitchInput(RVP_UavLoiterGuidance.compensateInput(pitchTarget, -xStep).setterValue());
            fixedWing.setYawInput(RVP_UavLoiterGuidance.compensateInput(out.yawInput(), yStep).setterValue());
            fixedWing.setRollInput(RVP_UavLoiterGuidance.compensateInput(rollTarget, -zStep).setterValue());
            uav.controlUnit.yRot = out.targetYRot();
            uav.controlUnit.yRotKeep = false;
        } else {
            uav.controlUnit.leftYaw = out.leftYaw();
            uav.controlUnit.rightYaw = out.rightYaw();
            uav.controlUnit.yRot = out.targetYRot();
            uav.controlUnit.yRotKeep = false;
        }
    }

    /** 只按传感器判据切换阶段；阶段计时器不跳过捕获或恢复条件。 */
    private static void updatePhase(MutableState state, GuidanceOutput out, int tickCount) {
        LoiterPhase current = state.phase;
        LoiterPhase next = out.nextPhase();
        if (next != current) {
            state.phase = next;
            state.phaseTickCounter = 0;
            state.phaseTimeout = resolvePhaseTimeout(next);
        } else {
            state.phaseTickCounter++;
            // 超时仅重新评估当前阶段；不可跳过捕获条件盲进盘旋。
            if (state.phaseTickCounter > state.phaseTimeout) {
                state.phaseTickCounter = 0;
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
        double speed = Math.sqrt(uav.getDeltaMovement().horizontalDistanceSqr());
        double lookAhead = Math.min(80.0, Math.max(24.0, speed * 20.0 * 2.0));
        Vec3 forward = uav.getLookAngle().scale(lookAhead);
        double aheadX = uav.getX() + forward.x;
        double aheadZ = uav.getZ() + forward.z;
        double aheadTerrain = uav.level().hasChunkAt(
                net.minecraft.core.BlockPos.containing(aheadX, uav.getY(), aheadZ))
                ? sampleTerrainAt(uav.level(), aheadX, aheadZ) : terrainY;
        return Math.max(altFromCenter, Math.max(terrainY, aheadTerrain) + TERRAIN_CLEARANCE);
    }

    /** 采样正下方地形高度。 */
    private static double sampleTerrainAt(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE,
                net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z));
    }

    /** 阶段最长计数仅用于周期性计数回绕，不改变制导阶段或捕获条件。 */
    private static int resolvePhaseTimeout(LoiterPhase phase) {
        return switch (phase) {
            case CLIMB -> CLIMB_TIMEOUT_TICKS;
            case TRANSIT -> TRANSIT_TIMEOUT_TICKS;
            case APPROACH -> APPROACH_TIMEOUT_TICKS;
            case LOITER -> Integer.MAX_VALUE;
            case RECOVERY -> 600;
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
