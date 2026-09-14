package org.ywzj.rvp.server.warn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CMissileTrackAlert;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.UUID;

/**
 * 激光照射告警（服务端）。
 *
 * <p>复用本体 SACL 激光指示会话（{@link RVP_SaclosOperatorSession}）：客户端每 tick 把照射点
 * 同步到服务端，本服务每隔若干 tick 扫描所有载具，检测是否有<b>敌对</b>玩家的照射点命中本车：
 * <ul>
 *   <li>方法 A：照射点落入本车包围盒膨胀 {@code LASER_POINT_RADIUS}（默认 10 格）内；</li>
 *   <li>方法 B：本车包围盒处于“敌方载具 → 照射点”连线中段（线段穿 AABB）。</li>
 * </ul>
 * 命中即向本车乘客补发无钳制的激光照射告警（{@link S2CMissileTrackAlert#TYPE_LASER}），
 * 客户端播放 {@code laser_alert.ogg} 并显示“被激光照射”提示。友方照射不告警。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_LaserWarnService {

    /** 补发扫描间隔（tick）：每 5 tick 一次，小于客户端 1.5s 告警窗口。 */
    private static final int SCAN_INTERVAL_TICK = 5;
    /** 激光点距本车判定半径（格）：照射点落入本车膨胀该半径内即视为照射命中。 */
    private static final double LASER_POINT_RADIUS = 10.0;
    /** 照射点新鲜度（毫秒）：超过该值视为已停止照射（客户端每 tick 同步）。 */
    private static final long LASER_FRESH_MS = 1000;

    private RVP_LaserWarnService() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % SCAN_INTERVAL_TICK != 0) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level);
        }
    }

    private static void scanLevel(ServerLevel level) {
        // 敌方活跃照射点（玩家 UUID → 照射点），超时视为停止照射
        Map<UUID, Vec3> designations = RVP_SaclosOperatorSession.activeDesignations(LASER_FRESH_MS);
        if (designations.isEmpty()) {
            return;
        }
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof AbstractVehicle vehicle) || vehicle.isDestroyed() || !vehicle.isAlive()) {
                continue;
            }
            for (Map.Entry<UUID, Vec3> entry : designations.entrySet()) {
                ServerPlayer laserUser = level.getServer().getPlayerList().getPlayer(entry.getKey());
                if (laserUser == null || !laserUser.isAlive()) {
                    continue;
                }
                // 目的：激光告警仅针对激光系武器（LBR 驾束 / LH / SALH）的照射——SACLOS 视线指令
                // 等其他操作手制导复用同一瞄准会话，不视为激光照射（2026-09-13 需求澄清）。
                // 解析失败（未乘载具 / 无武器站 / 非本項武器）按 fail-closed 跳过，宁漏不误。
                if (!isLaserGuidanceOperator(laserUser)) {
                    continue;
                }
                // 自己的载具不受自己的激光点告警（激光操作员就坐在该载具上时跳过）
                if (vehicle.hasPassenger(laserUser)) {
                    continue;
                }
                // 友方照射不告警
                if (vehicle.getTeam() != null && laserUser.getTeam() != null
                        && laserUser.getTeam().isAlliedTo(vehicle.getTeam())) {
                    continue;
                }
                if (isIlluminated(vehicle, laserUser, entry.getValue())) {
                    broadcastLaser(vehicle);
                    break; // 本车已被任一激光照射，跳出照射点循环
                }
            }
        }
    }

    /**
     * 照射者当前武器是否激光系（LBR/LH/SALH）。
     * 服务端权威解析（客户端会话不含武器类型）：玩家所乘载具 → 玩家操作位武器站 → 当前武器。
     * 非本項武器或解析失败一律返回 false（fail-closed）。
     */
    private static boolean isLaserGuidanceOperator(ServerPlayer laserUser) {
        // 目的：SACLOS 等操作手制导与激光系共用瞄准会话，告警面在此按武器制导类型收窄
        if (!(laserUser.getVehicle() instanceof AbstractVehicle vehicle)) {
            return false;
        }
        if (!(vehicle.getOwnOperatorUnit(laserUser) instanceof WeaponUnit operatorUnit)) {
            return false;
        }
        return operatorUnit.getCurrentWeapon()
                .map(weapon -> weapon.getData() instanceof RVP_WeaponData data && data.isLaserGuidanceType())
                .orElse(false);
    }

    /** 被照射判定：激光点距本车 ≤10m，或本车 AABB 处于“敌方载具→激光点”连线中段。 */
    private static boolean isIlluminated(AbstractVehicle vehicle, ServerPlayer laserUser, Vec3 point) {
        AABB box = vehicle.getBoundingBox();
        // 方法 A：激光点距本车 10m 内
        if (box.inflate(LASER_POINT_RADIUS).contains(point.x, point.y, point.z)) {
            return true;
        }
        // 方法 B：本车 AABB 处于敌方载具到激光点连线（线段穿 AABB）
        Entity mount = laserUser.getVehicle();
        if (mount != null) {
            Vec3 src = mount.position();
            if (box.clip(src, point).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static void broadcastLaser(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CMissileTrackAlert(0, vehicle.getId(), S2CMissileTrackAlert.TYPE_LASER));
            }
        }
    }
}
