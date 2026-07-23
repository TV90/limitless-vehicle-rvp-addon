package org.ywzj.rvp.uav;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.core.BlockPos;
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
import org.ywzj.vehicle.util.EntityUtil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 无人机盘旋服务端 tick 处理器。遍历 active UAV，计算制导并写入 ControlUnit。
 * <p>非 Mixin，通过 Forge 事件总线挂载。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_UavLoiterTickService {

    private RVP_UavLoiterTickService() {}

    /** 地形采样间隔（tick）。 */
    private static final int TERRAIN_SAMPLE_INTERVAL = 40;
    /** 地形采样前方范围（格）。 */
    private static final int TERRAIN_SAMPLE_RANGE = 60;
    /** 地形采样步长（格）。 */
    private static final int TERRAIN_SAMPLE_STEP = 10;
    /** 客户端同步间隔（tick）。 */
    private static final int SYNC_INTERVAL_TICKS = 10;

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
            // 查找 UAV 实体
            AbstractVehicle uav = resolveVehicle(server, uavUuid);
            if (uav == null || !uav.isAlive() || uav.isRemoved()) {
                RVP_UavLoiterManager.remove(uavUuid);
                continue;
            }
            // 盘旋激活时制导优先；玩家运动输入由 ControlUnitMixin 屏蔽，
            // 玩家仍可操作武器（functional 输入）和按 F 键退出盘旋
            // 获取配置
            RVP_LoiterConfig config = resolveConfig(uav);
            // 更新圆心（跟随母车模式）
            updateCenterIfFollowing(server, state);
            // 计算目标高度（三重基准取最大值）
            double targetAltitude = resolveTargetAltitude(uav, state, config);
            // 制导计算
            boolean isRotaryWing = uav instanceof RotaryWingVehicle;
            GuidanceOutput out = computeGuidance(uav, state, targetAltitude, isRotaryWing, tickCount, config);
            // 写入 ControlUnit
            applyControlUnit(uav, out, isRotaryWing);
            // 阶段切换
            updatePhase(state, out, tickCount, config);
            // 震荡检测
            updateOscillation(state, out);
            // 区块加载保持
            EntityUtil.keepChunkLoaded(uav, uav.position());
        }

        // 定期同步盘旋状态到客户端
        if (server.getTickCount() % SYNC_INTERVAL_TICKS == 0) {
            syncLoiterStateToClients(server);
        }
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
                    dim, state.centerX, state.centerZ, state.radius, state.active));
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
        double radius = resolveActualRadius(uav, state.radius, config, isRotaryWing);

        return switch (state.phase) {
            case CLIMB -> RVP_UavLoiterGuidance.computeClimb(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    targetAltitude, config.loiterMinSafeAltitude(), isRotaryWing);
            case TRANSIT -> RVP_UavLoiterGuidance.computeTransit(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, isRotaryWing);
            case APPROACH -> RVP_UavLoiterGuidance.computeApproach(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, isRotaryWing, tickCount);
            case LOITER -> RVP_UavLoiterGuidance.computeLoiter(
                    uavX, uavY, uavZ, uavYaw,
                    state.centerX, state.centerY, state.centerZ,
                    radius, targetAltitude, isRotaryWing, tickCount,
                    state.snapshot(),
                    uav.getZRot());
        };
    }

    /** 写入 ControlUnit。 */
    private static void applyControlUnit(AbstractVehicle uav, GuidanceOutput out, boolean isRotaryWing) {
        uav.controlUnit.reset();
        uav.controlUnit.forward = out.forward();
        uav.controlUnit.up = out.up();
        uav.controlUnit.down = out.down();
        uav.controlUnit.left = out.left();
        uav.controlUnit.right = out.right();
        if (out.useAnalogYaw()) {
            // 旋翼机：设置目标偏航角，物理引擎自动平滑追踪
            uav.controlUnit.yRot = out.targetYRot();
            uav.controlUnit.yRotKeep = false;
        } else {
            // 固定翼：离散偏航控制
            uav.controlUnit.leftYaw = out.leftYaw();
            uav.controlUnit.rightYaw = out.rightYaw();
            // 设置目标航向供本体自动协调逻辑使用，避免 reset() 后 yRot=0 干扰滚转
            uav.controlUnit.yRot = out.targetYRot();
            uav.controlUnit.yRotKeep = false;
        }
    }

    /** 阶段切换 + 超时降级。 */
    private static void updatePhase(MutableState state, GuidanceOutput out, int tickCount, RVP_LoiterConfig config) {
        LoiterPhase current = state.phase;
        LoiterPhase next = out.nextPhase();
        if (next != current) {
            state.phase = next;
            state.phaseTickCounter = 0;
            state.phaseTimeout = resolvePhaseTimeout(next, config);
        } else {
            state.phaseTickCounter++;
            // 超时降级
            if (state.phaseTickCounter > state.phaseTimeout) {
                state.phase = nextPhase(current);
                state.phaseTickCounter = 0;
                state.phaseTimeout = resolvePhaseTimeout(state.phase, config);
            }
        }
    }

    /** 震荡检测：yaw 误差符号翻转计数。仅旋翼机（模拟偏航）适用。 */
    private static void updateOscillation(MutableState state, GuidanceOutput out) {
        if (state.phase != LoiterPhase.LOITER) {
            return;
        }
        // 固定翼 targetYRot 为绝对切线航向，符号无误差含义，跳过检测
        if (!out.useAnalogYaw()) {
            return;
        }
        // 旋翼机用 targetYRot - currentYaw 估算误差
        // 这里简化：用 signFlipCounter 做粗略检测
        // 实际震荡检测在制导层用 lastYawError 比较符号
        if (state.lastYawError != 0) {
            float currentError = Mth.wrapDegrees(out.targetYRot());
            if (Math.signum(currentError) != Math.signum(state.lastYawError) && Math.abs(currentError) > 1f) {
                state.signFlipCounter++;
            } else {
                state.signFlipCounter = Math.max(0, state.signFlipCounter - 1);
            }
        }
        state.lastYawError = Mth.wrapDegrees(out.targetYRot());
        // 震荡时扩大半径
        if (state.signFlipCounter > 3) {
            state.radius *= 1.1;
            state.signFlipCounter = 0;
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

    /** 计算目标高度（三重基准取最大值）。 */
    private static double resolveTargetAltitude(AbstractVehicle uav, MutableState state, RVP_LoiterConfig config) {
        double altFromCenter = state.centerY + config.loiterAltitudeOffset();
        double minSafe = config.loiterMinSafeAltitude();
        // 地形高度（当前正下方）
        double terrainY = sampleTerrainAt(uav.level(), uav.getX(), uav.getZ());
        double altFromTerrain = terrainY + config.loiterTerrainClearance();
        return Math.max(altFromCenter, Math.max(altFromTerrain, minSafe));
    }

    /** 采样正下方地形高度。 */
    private static double sampleTerrainAt(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, (int) x, (int) z);
    }

    /** 计算实际半径（载具类型 clamp）。 */
    private static double resolveActualRadius(AbstractVehicle uav, double configuredRadius,
                                              RVP_LoiterConfig config, boolean isRotaryWing) {
        if (isRotaryWing) {
            return Math.max(configuredRadius, 30.0);
        }
        // 固定翼：根据当前速度动态计算最小半径
        double hSpeed = Math.sqrt(uav.getDeltaMovement().horizontalDistanceSqr());
        double minR = RVP_UavLoiterGuidance.resolveFixedWingMinRadius(hSpeed, config.loiterFixedWingMinBank());
        return Math.max(configuredRadius, minR);
    }

    /** 阶段超时阈值。 */
    private static int resolvePhaseTimeout(LoiterPhase phase, RVP_LoiterConfig config) {
        return switch (phase) {
            case CLIMB -> config.loiterClimbTimeout();
            case TRANSIT -> config.loiterTransitTimeout();
            case APPROACH -> config.loiterApproachTimeout();
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
        if (uav instanceof org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt ext) {
            UUID parentUuid = ext.ywzj_rvp$getLinkedParentVehicleUuid();
            if (parentUuid != null) {
                AbstractVehicle parent = resolveVehicle(uav.getServer(), parentUuid);
                if (parent != null) {
                    RVP_LoiterConfig parentConfig = RVP_LoiterConfigCache.get(parent.getVehicleId());
                    if (parentConfig.isConfigured()) {
                        return parentConfig;
                    }
                }
            }
        }
        // 回退到载具自身配置（AC130、空中炮艇等通用盘旋场景）
        return RVP_LoiterConfigCache.get(uav.getVehicleId());
    }
}
