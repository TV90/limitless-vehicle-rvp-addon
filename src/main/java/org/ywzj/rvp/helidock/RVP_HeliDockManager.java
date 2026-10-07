package org.ywzj.rvp.helidock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CHeliDockState;
import org.ywzj.rvp.util.RVP_ServerVehicleIndex;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.entity.vehicle.VesselVehicle;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直升机舰船着舰管理器（2026-10-07 新功能）。
 * <p><b>流程</b>：驾驶直升机接近带 {@code rvp_helipads} 的舰船 48 格内 → actionbar 提示 →
 * 按 P 进入 {@link Phase#APPROACH}（服务端写 controlUnit 自动飞向停机坪，玩家运动输入被
 * {@code ControlUnitMixin} 屏蔽，{@code collision=false} 免两车推挤/撞击）→ 水平 5 格内
 * 瞬移吸附 → {@link Phase#DOCKED}（每 tick 同步 pos/yaw 到停机坪世界坐标，随舰船行驶转向，
 * deltaMovement 清零防重力下坠——甲板实体不提供 onGround 支撑）→ 再按 P 进入
 * {@link Phase#TAKEOFF}（自动爬升到相对甲板 15 格）→ 解锁玩家操控并恢复 collision。</p>
 * <p><b>绑定方式说明</b>：本体 {@code AbstractVehicle.addPassenger} 只接受 LivingEntity，
 * 载具骑载具会被静默丢弃（调研证据），故采用"独立实体 + 每 tick 强制同步"（本体吊运
 * {@code RotaryWingVehicle.tickCargo} 同款模式）。着舰态保留可被武器伤害（collision=false
 * 只关推挤/撞击，不免疫武器）；两车间撞击伤害由 {@code RVP_HeliDockEventHandler} 经
 * {@code VehicleAttackEvent} 拦截。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HeliDockManager {

    private RVP_HeliDockManager() {
    }

    /** 着舰流程阶段。 */
    public enum Phase { APPROACH, DOCKED, TAKEOFF }

    /** 接近提示与按 P 的有效范围（格，水平距离）。 */
    public static final double PROMPT_RANGE = 48.0;
    /** 吸附着舰的水平触发距离（格）：进入即吸附（吸附时本就传送到标准位），用户实测 5→10 仍打转，2026-10-07 再调大到 15。 */
    public static final double SNAP_RANGE = 15.0;
    /** 起飞解锁所需的相对甲板高度（格）。 */
    public static final double TAKEOFF_HEIGHT = 15.0;
    /** 接近段的目标悬停高度（停机坪中心上方，格）。 */
    private static final double APPROACH_HOVER_HEIGHT = 6.0;
    /** 接近段判定"已到位"的水平距离（格）。 */
    private static final double APPROACH_ARRIVE_H = 8.0;
    /** 接近段开始向坪面下降的水平距离（格）：比吸附门近，避免远距就贴甲板平飞穿舰体。 */
    public static final double DESCENT_RANGE = 8.0;
    /** 吸附时相对坪面高度的宽松门（格）：直升机原点悬停在甲板上方即算到位（吸附时传送到标准位，无需苛刻）。 */
    public static final double DOCK_VERTICAL_MIN = -3.0;
    public static final double DOCK_VERTICAL_MAX = 7.0;
    /** 吸附范围内的水平速度阻尼系数（每 tick 乘算）：抑制近距离模拟偏航回转率不足导致的绕圈过冲。 */
    private static final double SNAP_DAMPING = 0.9;
    /** 提示/驱动扫描间隔（tick）。 */
    private static final int SCAN_INTERVAL_TICKS = 10;
    /** 舰船失援兜底宽限（tick）：舰船实体查不到持续超过该值即解除着舰。 */
    private static final int SHIP_MISSING_GRACE_TICKS = 100;

    /** 着舰状态（key = 直升机 UUID）。 */
    public record DockingState(UUID shipUuid, String padBone, Phase phase, int lostTicks) {
    }

    private static final Map<UUID, DockingState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SHIP_MISSING_TICKS = new HashMap<>();

    /** 该直升机是否处于控制锁定（APPROACH/DOCKED/TAKEOFF 任一阶段）。 */
    public static boolean isControlLocked(UUID heliUuid) {
        return heliUuid != null && STATES.containsKey(heliUuid);
    }

    /** 读取指定直升机的着舰状态（无则 null）。 */
    public static DockingState stateOf(UUID heliUuid) {
        return heliUuid == null ? null : STATES.get(heliUuid);
    }

    /** 该直升机是否已着舰锁定（DOCKED）。 */
    public static boolean isDocked(UUID heliUuid) {
        DockingState state = heliUuid == null ? null : STATES.get(heliUuid);
        return state != null && state.phase() == Phase.DOCKED;
    }

    /** 指定舰船是否停靠了直升机（着舰位互斥用）。 */
    public static boolean isShipOccupied(UUID shipUuid) {
        if (shipUuid == null) {
            return false;
        }
        for (DockingState state : STATES.values()) {
            if (state.shipUuid().equals(shipUuid) && state.phase() == Phase.DOCKED) {
                return true;
            }
        }
        return false;
    }

    /** 按 P 切换（服务端，已校验玩家驾驶旋翼机）。返回是否接受。 */
    public static boolean toggle(ServerPlayer player, RotaryWingVehicle heli) {
        UUID heliUuid = heli.getUUID();
        DockingState state = STATES.get(heliUuid);
        if (state != null) {
            switch (state.phase()) {
                case APPROACH -> {
                    release(heli);
                    player.displayClientMessage(Component.translatable(
                            "message.ywzj_rvp.helidock.approach_cancelled"), true);
                    return true;
                }
                case DOCKED -> {
                    STATES.put(heliUuid, new DockingState(state.shipUuid(), state.padBone(),
                            Phase.TAKEOFF, 0));
                    // 起飞自动打开发动机并满功率（着舰时已自动关闭，2026-10-07 用户定版）
                    heli.toggleEngine(true);
                    heli.setPower(100f);
                    syncClient(player, true);
                    player.displayClientMessage(Component.translatable(
                            "message.ywzj_rvp.helidock.takeoff"), true);
                    return true;
                }
                default -> {
                    return false; // TAKEOFF 中不响应
                }
            }
        }
        // 未着舰：找 48 格内最近的停机坪（按 pad 骨骼 OBB 中心判定）
        NearestHelipad nearest = findNearestHelipad(heli, player.serverLevel());
        if (nearest == null) {
            return false;
        }
        STATES.put(heliUuid, new DockingState(nearest.ship().getUUID(),
                nearest.padBone(), Phase.APPROACH, 0));
        SHIP_MISSING_TICKS.remove(heliUuid);
        heli.collision = false;
        syncClient(player, true);
        player.displayClientMessage(Component.translatable(
                "message.ywzj_rvp.helidock.approach"), true);
        return true;
    }

    /** 服务端每 tick 驱动（RVP_UavLoiterTickService 同款 ServerTickEvent END 挂法）。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, DockingState> entry : STATES.entrySet()) {
            UUID heliUuid = entry.getKey();
            DockingState state = entry.getValue();
            AbstractVehicle heli = resolveVehicle(server, heliUuid);
            if (heli == null || !heli.isAlive() || heli.isRemoved()) {
                continue; // 直升机实体缺失（区块卸载）：保留状态，实体回来继续流程
            }
            AbstractVehicle ship = resolveVehicle(server, state.shipUuid());
            if (ship == null || !ship.isAlive() || ship.isRemoved()) {
                int missing = SHIP_MISSING_TICKS.merge(heliUuid, 1, Integer::sum);
                if (missing > SHIP_MISSING_GRACE_TICKS) {
                    SHIP_MISSING_TICKS.remove(heliUuid);
                    release((RotaryWingVehicle) heli);
                    notifyRider(heli, "message.ywzj_rvp.helidock.ship_lost");
                }
                continue;
            }
            SHIP_MISSING_TICKS.remove(heliUuid);
            Vec3 padCenter = resolvePadWorldCenter(ship, state.padBone());
            if (padCenter == null) {
                continue;
            }
            switch (state.phase()) {
                case APPROACH -> tickApproach((RotaryWingVehicle) heli, padCenter);
                case DOCKED -> tickDocked(heli, ship, padCenter);
                case TAKEOFF -> tickTakeoff((RotaryWingVehicle) heli, padCenter);
            }
        }
        // 接近提示扫描（低频）
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanProximityPrompt(server);
        }
    }

    /**
     * 接近段（2026-10-07 重写，参照 Gunner 旋翼飞控 tickRotaryDriving/tickRotaryCruise）：
     * 偏航 = controlUnit.yRot 目标方向（旋翼模拟偏航自追踪）；前后 = controlUnit.xRot 目标俯仰角
     * （W/S 的 AI 等价物是压杆角度而非 forward 布尔）。
     * 总距 = 垂直速度闭环（2026-10-07 用户定版：下降率 3 m/s，接近坪面收敛到 1 m/s，按实际
     * 下降率差值补杆）；硬性防坠地板 3.6 m/s。
     * 目标点：8 格外悬停坪上 6 格；进入下降边界（8 格）转坪面+1 下降对接。
     * 吸附门：下降段内水平 ≤8 格 ∧ 高度到目标面 [−2, +2.5]。
     */
    private static void tickApproach(RotaryWingVehicle heli, Vec3 padCenter) {
        var cu = heli.controlUnit;
        cu.reset();
        double horizontalDist = Math.sqrt(
                Math.pow(padCenter.x - heli.getX(), 2) + Math.pow(padCenter.z - heli.getZ(), 2));
        double targetY = horizontalDist <= DESCENT_RANGE
                ? padCenter.y + 1.0
                : padCenter.y + APPROACH_HOVER_HEIGHT;
        org.joml.Vector2f targetRot = toRot(new Vec3(padCenter.x - heli.getX(),
                targetY - heli.getY(),
                padCenter.z - heli.getZ()));
        cu.yRot = targetRot.y;
        cu.yRotKeep = false;
        cu.xRot = Mth.clamp(targetRot.x * 0.75F, -10.0F, 10.0F);
        cu.xRotKeep = false;
        // 总距：垂直速度闭环——目标爬升率 = clamp(高度误差 × 0.5, [-3, +4]) m/s，
        // 按实际垂直速度差值补杆（降太快提总距缓冲 / 降太慢压总距加快），带 ±2 m/s 外死区与 0.4 m/s 内死区
        double vyMps = heli.getDeltaMovement().y * 20.0;
        double heightAboveTarget = heli.getY() - targetY;
        double targetClimbRate = Mth.clamp(heightAboveTarget * 0.5F, -3.0F, 4.0F);
        double climbRateError = targetClimbRate - vyMps;
        if (heli.getCollectivePitch() < 55.0f) {
            cu.up = true;
        } else if (heightAboveTarget < -1.0) {
            cu.up = true;
        } else if (heightAboveTarget > 0.5) {
            if (climbRateError > 0.4) {
                cu.up = true;
            } else if (climbRateError < -0.4) {
                cu.down = true;
            }
        }
        // 硬性防坠地板：下沉超过 3.6 m/s 强拉（高于目标下降率上限，不干扰闭环）
        double vy = heli.getDeltaMovement().y;
        if (vy < -0.18) {
            cu.xRot = Math.min(cu.xRot, -4.0f);
            cu.up = true;
        }
        // 吸附门：下降段内水平 ≤8 格 ∧ 高度到目标面 [−2, +2.5]（目标面 = 坪面 +1）
        if (horizontalDist <= DESCENT_RANGE
                && horizontalDist <= 8.0
                && heightAboveTarget <= 2.5
                && heightAboveTarget >= -2.0) {
            dock(heli);
            return;
        }
        // 未进下降段的接近飞行：轻阻尼（0.9/tick）抑制绕圈过冲，不强压俯仰
        if (horizontalDist <= SNAP_RANGE) {
            Vec3 mv = heli.getDeltaMovement();
            heli.setDeltaMovement(new Vec3(mv.x * SNAP_DAMPING, mv.y, mv.z * SNAP_DAMPING));
        }
    }

    /** 方向向量 → (俯仰, 偏航) 角（度），本体 VectorUtil.vecToRot 同款语义（俯仰负=朝下看）。 */
    private static org.joml.Vector2f toRot(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontal));
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        return new org.joml.Vector2f(pitch, yaw);
    }

    /** 吸附着舰：瞬移到 pad 中心上方并切 DOCKED。 */
    private static void dock(RotaryWingVehicle heli) {
        UUID heliUuid = heli.getUUID();
        DockingState state = STATES.get(heliUuid);
        if (state == null) {
            return;
        }
        STATES.put(heliUuid, new DockingState(state.shipUuid(), state.padBone(), Phase.DOCKED, 0));
        // 着舰完毕自动关闭发动机（2026-10-07 用户定版）：功率归零旋翼停转，下次起飞再开启
        heli.toggleEngine(false);
        heli.setPower(0f);
        notifyRider(heli, "message.ywzj_rvp.helidock.docked");
    }

    /** 着舰锁定：每 tick 同步 pos 到停机坪世界坐标、yaw 随舰船（行驶转向均跟随），清速度防下坠。 */
    private static void tickDocked(AbstractVehicle heli, AbstractVehicle ship, Vec3 padCenter) {
        heli.teleportTo(padCenter.x, padCenter.y, padCenter.z);
        heli.setYRot(ship.getYRot());
        heli.setDeltaMovement(Vec3.ZERO);
        heli.fallDistance = 0.0f;
        heli.controlUnit.reset();
    }

    /** 起飞（gunner 起飞同款：悬停模式自稳 + 总距上升 + 保持姿态），相对甲板 15 格解锁。 */
    private static void tickTakeoff(RotaryWingVehicle heli, Vec3 padCenter) {
        double relHeight = heli.getY() - padCenter.y;
        var cu = heli.controlUnit;
        cu.reset();
        heli.hoverMode = true;
        cu.up = true;
        cu.xRotKeep = true;
        cu.yRotKeep = true;
        if (relHeight >= TAKEOFF_HEIGHT) {
            heli.hoverMode = false;
            release(heli);
            notifyRider(heli, "message.ywzj_rvp.helidock.released");
        }
    }

    /** 解除着舰/接管：清 controlUnit、恢复碰撞、移除状态并同步客户端。 */
    private static void release(RotaryWingVehicle heli) {
        STATES.remove(heli.getUUID());
        SHIP_MISSING_TICKS.remove(heli.getUUID());
        heli.collision = true;
        heli.controlUnit.reset();
        if (heli.getDriver() instanceof ServerPlayer player) {
            syncClient(player, false);
        }
    }

    /** 接近提示：驾驶直升机且 48 格内有带停机坪舰船的玩家 → actionbar 提示。 */
    private static void scanProximityPrompt(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (!(player.getVehicle() instanceof RotaryWingVehicle heli)
                        || STATES.containsKey(heli.getUUID())) {
                    continue;
                }
                if (findNearestHelipad(heli, level) != null) {
                    player.displayClientMessage(Component.translatable(
                            "message.ywzj_rvp.helidock.prompt"), true);
                }
            }
        }
    }

    /** 最近停机坪查询结果：舰船 + 命中的 pad 骨名 + pad 世界中心。 */
    public record NearestHelipad(AbstractVehicle ship, String padBone, Vec3 padCenter) {
    }

    /**
     * 找 48 格内最近的停机坪（2026-10-07 修正：判定距离 = 直升机到 **pad 骨骼 OBB 中心**，
     * 不再是舰船实体中心——停机坪在舰首/舰尾时舰心距离会严重失真）。
     * 逐舰逐 pad 现算世界中心取最近；未停靠其它直升机的舰船才参与。
     */
    private static NearestHelipad findNearestHelipad(RotaryWingVehicle heli, ServerLevel level) {
        NearestHelipad nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : RVP_ServerVehicleIndex.getVehicles(level)) {
            if (!(vehicle instanceof VesselVehicle) || !vehicle.isAlive() || vehicle.isRemoved()) {
                continue;
            }
            List<String> pads = helipadBones(vehicle);
            if (pads.isEmpty()) {
                continue;
            }
            for (String padBone : pads) {
                Vec3 padCenter = resolvePadWorldCenter(vehicle, padBone);
                if (padCenter == null) {
                    continue;
                }
                double distSqr = heli.position().distanceToSqr(padCenter);
                if (distSqr > PROMPT_RANGE * PROMPT_RANGE || distSqr >= nearestDistSqr) {
                    continue;
                }
                nearestDistSqr = distSqr;
                nearest = new NearestHelipad(vehicle, padBone, padCenter);
            }
        }
        return nearest;
    }

    /** 舰船的停机坪骨骼名列表（配置为空返回空列表）。 */
    private static List<String> helipadBones(AbstractVehicle ship) {
        var config = RVP_VehicleExtendedConfigManager.INSTANCE.get(ship);
        return config == null ? List.of() : List.copyOf(config.helipadBones());
    }

    /**
     * 解析停机坪骨骼的世界系中心（OBB 集合的平均中心；随舰船实时位姿现算）。
     * 结构模型/骨骼缺失返回 null。
     */
    private static Vec3 resolvePadWorldCenter(AbstractVehicle ship, String padBone) {
        if (padBone == null || padBone.isBlank()) {
            return null;
        }
        var config = RVP_VehicleExtendedConfigManager.INSTANCE.get(ship);
        ResourceLocation structureModel = config == null ? null : config.structureModel();
        if (structureModel == null) {
            return null;
        }
        BedrockModel model = CommonAssetsManager.structureModelManager()
                .getStructureModel(structureModel).orElse(null);
        if (model == null) {
            return null;
        }
        BedrockBone bone = model.getBoneMap().get(padBone);
        if (bone == null) {
            return null;
        }
        java.util.HashSet<BedrockBone> namedBones = new java.util.HashSet<>(model.getBoneMap().values());
        List<OBB.CubeOBB> obbs = OBB.getOBBsFromBone(bone, ship, namedBones);
        if (obbs.isEmpty()) {
            return null;
        }
        // OBB.center() 返回 JOML Vector3f（公开字段 x/y/z）
        double sx = 0, sy = 0, sz = 0;
        for (OBB.CubeOBB cubeObb : obbs) {
            org.joml.Vector3f c = cubeObb.obb().center();
            sx += c.x;
            sy += c.y;
            sz += c.z;
        }
        return new Vec3(sx / obbs.size(), sy / obbs.size(), sz / obbs.size());
    }

    /** 给机上玩家发 actionbar 提示。 */
    private static void notifyRider(AbstractVehicle heli, String key) {
        if (heli.getDriver() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    /** 同步着舰状态到机上玩家客户端（输入抑制与 UI 用）。 */
    private static void syncClient(ServerPlayer player, boolean locked) {
        RVP_Network.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new S2CHeliDockState(locked));
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
}
