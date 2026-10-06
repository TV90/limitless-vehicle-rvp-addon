package org.ywzj.rvp.helidock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    /** 吸附着舰的水平触发距离（格）。 */
    public static final double SNAP_RANGE = 5.0;
    /** 起飞解锁所需的相对甲板高度（格）。 */
    public static final double TAKEOFF_HEIGHT = 15.0;
    /** 接近段的目标悬停高度（停机坪中心上方，格）。 */
    private static final double APPROACH_HOVER_HEIGHT = 6.0;
    /** 接近段判定"已到位"的水平距离（格）。 */
    private static final double APPROACH_ARRIVE_H = 8.0;
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
        // 未着舰：找 48 格内最近的有停机坪舰船
        AbstractVehicle ship = findNearestHelipadShip(heli, player.serverLevel());
        if (ship == null) {
            return false;
        }
        String padBone = nearestPadBone(ship, heli.position());
        STATES.put(heliUuid, new DockingState(ship.getUUID(), padBone, Phase.APPROACH, 0));
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
                case DOCKED -> tickDocked(heli, padCenter);
                case TAKEOFF -> tickTakeoff((RotaryWingVehicle) heli, padCenter);
            }
        }
        // 接近提示扫描（低频）
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanProximityPrompt(server);
        }
    }

    /** 接近段：写 controlUnit 自动飞向停机坪上空，水平到位即吸附。 */
    private static void tickApproach(RotaryWingVehicle heli, Vec3 padCenter) {
        double dx = padCenter.x - heli.getX();
        double dz = padCenter.z - heli.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = hDist > 0.5
                ? (float) Math.toDegrees(Math.atan2(dx, -dz))
                : heli.getYRot();
        double altError = (padCenter.y + APPROACH_HOVER_HEIGHT) - heli.getY();
        var cu = heli.controlUnit;
        cu.reset();
        cu.yRot = targetYaw;
        cu.yRotKeep = false;
        cu.forward = hDist > APPROACH_ARRIVE_H;
        cu.up = altError > 2;
        cu.down = altError < -2;
        if (hDist <= SNAP_RANGE && Math.abs(altError) < 8) {
            dock(heli);
        }
    }

    /** 吸附着舰：瞬移到 pad 中心上方并切 DOCKED。 */
    private static void dock(RotaryWingVehicle heli) {
        UUID heliUuid = heli.getUUID();
        DockingState state = STATES.get(heliUuid);
        if (state == null) {
            return;
        }
        STATES.put(heliUuid, new DockingState(state.shipUuid(), state.padBone(), Phase.DOCKED, 0));
        notifyRider(heli, "message.ywzj_rvp.helidock.docked");
    }

    /** 着舰锁定：每 tick 同步 pos/yaw 到停机坪世界坐标（随舰船行驶转向），清速度防下坠。 */
    private static void tickDocked(AbstractVehicle heli, Vec3 padCenter) {
        heli.teleportTo(padCenter.x, padCenter.y, padCenter.z);
        heli.setDeltaMovement(Vec3.ZERO);
        heli.fallDistance = 0.0f;
        heli.controlUnit.reset();
    }

    /** 起飞：自动爬升到相对甲板 15 格后解锁。 */
    private static void tickTakeoff(RotaryWingVehicle heli, Vec3 padCenter) {
        double relHeight = heli.getY() - padCenter.y;
        var cu = heli.controlUnit;
        cu.reset();
        cu.up = true;
        cu.forward = relHeight > TAKEOFF_HEIGHT * 0.5;
        if (relHeight >= TAKEOFF_HEIGHT) {
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
                AbstractVehicle ship = findNearestHelipadShip(heli, level);
                if (ship != null) {
                    player.displayClientMessage(Component.translatable(
                            "message.ywzj_rvp.helidock.prompt"), true);
                }
            }
        }
    }

    /** 找 48 格内最近的有停机坪舰船（未停靠其它直升机）。 */
    private static AbstractVehicle findNearestHelipadShip(RotaryWingVehicle heli, ServerLevel level) {
        AbstractVehicle nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : RVP_ServerVehicleIndex.getVehicles(level)) {
            if (!(vehicle instanceof VesselVehicle) || !vehicle.isAlive() || vehicle.isRemoved()) {
                continue;
            }
            List<String> pads = helipadBones(vehicle);
            if (pads.isEmpty()) {
                continue;
            }
            double dx = vehicle.getX() - heli.getX();
            double dz = vehicle.getZ() - heli.getZ();
            double distSqr = dx * dx + dz * dz;
            if (distSqr > PROMPT_RANGE * PROMPT_RANGE || distSqr >= nearestDist) {
                continue;
            }
            nearest = vehicle;
            nearestDist = distSqr;
        }
        return nearest;
    }

    /** 舰船的停机坪骨骼名列表（配置为空返回空列表）。 */
    private static List<String> helipadBones(AbstractVehicle ship) {
        var config = RVP_VehicleExtendedConfigManager.INSTANCE.get(ship);
        return config == null ? List.of() : List.copyOf(config.helipadBones());
    }

    /** 距参考点最近的停机坪骨名（全部解析失败回退第一个配置名）。 */
    private static String nearestPadBone(AbstractVehicle ship, Vec3 reference) {
        List<String> pads = helipadBones(ship);
        if (pads.isEmpty()) {
            return "";
        }
        String nearest = pads.get(0);
        double nearestDist = Double.MAX_VALUE;
        for (String bone : pads) {
            Vec3 center = resolvePadWorldCenter(ship, bone);
            if (center == null) {
                continue;
            }
            double dist = center.distanceToSqr(reference);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = bone;
            }
        }
        return nearest;
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
