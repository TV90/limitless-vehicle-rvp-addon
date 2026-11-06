package org.ywzj.rvp.server.warn;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CMissileTrackAlert;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 激光照射告警（服务端）。
 *
 * <p>复用本体 SACL 激光指示会话（{@link RVP_SaclosOperatorSession}）：客户端每 tick 把照射点
 * 同步到服务端，本服务每隔若干 tick 扫描所有载具，检测是否有<b>敌对</b>操作手（玩家或
 * AI gunner）的照射点命中本车：
 * <ul>
 *   <li>方法 A：照射点落入本车包围盒膨胀 {@code LASER_POINT_RADIUS}（默认 10 格）内；</li>
 *   <li>方法 B：本车包围盒处于“敌方载具 → 照射点”连线中段（线段穿 AABB）。</li>
 * </ul>
 * 命中即向本车乘客补发无钳制的激光照射告警（{@link S2CMissileTrackAlert#TYPE_LASER}），
 * 客户端播放 {@code laser_alert.ogg} 并显示“被激光照射”提示。友方照射不告警。
 *
 * <p>AI gunner 发射的激光架束/激光制导弹（如 GP125）不走客户端照射同步（2026-11-06 修复），
 * 因此另扫在途激光系制导弹：弹主为 Gunner、制导类型为 LBR/LH/SALH 且攻击目标为本车时，
 * 同样按节流补发告警；步行玩家被瞄准时也可收到告警。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_LaserWarnService {

    /** 补发扫描间隔（tick）：每 5 tick 一次，小于客户端 1.5s 告警窗口。 */
    private static final int SCAN_INTERVAL_TICK = 5;
    /** 激光点距本车判定半径（格）：照射点落入本车膨胀该半径内即视为照射命中。 */
    private static final double LASER_POINT_RADIUS = 10.0;
    /** 照射点新鲜度（毫秒）：超过该值视为已停止照射（客户端每 tick 同步）。 */
    private static final long LASER_FRESH_MS = 1000;
    /** 激光武器命中告警节流（tick）：同一"射手车→目标车"对每 10t 最多发一次（射击间隔可短至数 tick）。 */
    private static final long HIT_WARN_INTERVAL_TICK = 10L;
    /** 激光武器命中告警节流侧表："射手车id:目标车id" → 最近一次告警的 game time。 */
    private static final Map<String, Long> LAST_HIT_WARN = new HashMap<>();

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
        // 敌方活跃照射点（操作手 UUID → 照射点），超时视为停止照射
        Map<UUID, Vec3> designations = RVP_SaclosOperatorSession.activeDesignations(LASER_FRESH_MS);
        boolean hasDesignations = !designations.isEmpty();
        // 单次实体遍历同时处理两类来源：照射会话（玩家/AI 操作手）与在途激光制导弹。
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof AbstractVehicle vehicle) {
                if (hasDesignations && vehicle.isAlive()) {
                    scanDesignatedVehicle(level, vehicle, designations);
                }
            } else if (entity instanceof RVP_BaseBullet bullet) {
                // 2026-11-06：AI 炮手的 LBR/激光制导弹没有客户端照射同步，改由在途弹反查目标告警。
                scanLaserGuidedProjectile(bullet);
            }
        }
    }

    /** 照射会话命中载具的告警：遍历活跃照射点，任一激光系操作手照到本车即告警。 */
    private static void scanDesignatedVehicle(ServerLevel level, AbstractVehicle vehicle,
                                              Map<UUID, Vec3> designations) {
        if (vehicle.isDestroyed() || !vehicle.isAlive()) {
            return;
        }
        for (Map.Entry<UUID, Vec3> entry : designations.entrySet()) {
            Entity designator = level.getEntity(entry.getKey());
            if (!(designator instanceof LivingEntity user) || !user.isAlive()) {
                continue;
            }
            // 目的：激光告警仅针对激光系武器（LBR 驾束 / LH / SALH）的照射——SACLOS 视线指令
            // 等其他操作手制导复用同一瞄准会话，不视为激光照射（2026-09-13 需求澄清）。
            // 解析失败（未乘载具 / 无武器站 / 非本項武器）按 fail-closed 跳过，宁漏不误。
            if (!isLaserGuidanceOperator(user)) {
                continue;
            }
            Entity mount = user.getVehicle();
            if (mount == null) {
                continue;
            }
            // 自己的载具不受自己的激光点告警（照射者就坐在该载具上时跳过）
            if (vehicle.hasPassenger(user)) {
                continue;
            }
            // 友方照射不告警
            if (vehicle.getTeam() != null && user.getTeam() != null
                    && user.getTeam().isAlliedTo(vehicle.getTeam())) {
                continue;
            }
            if (isIlluminated(vehicle, mount.position(), entry.getValue())) {
                broadcastLaser(vehicle);
                break; // 本车已被任一激光照射，跳出照射点循环
            }
        }
    }

    /**
     * 在途激光制导弹告警：AI 炮手发射的 LBR（如 GP125 激光架束）没有照射会话，
     * 以"弹体仍在飞 + 激光系制导 + 攻击目标是本车"为照射判据，每 5 tick 刷新一次告警。
     */
    private static void scanLaserGuidedProjectile(RVP_BaseBullet bullet) {
        if (!bullet.isAlive() || bullet.level().isClientSide()) {
            return;
        }
        if (!(bullet.getOwner() instanceof GunnerEntity gunner) || !gunner.isAlive()) {
            return;
        }
        RVP_WeaponData data = bullet.getRvpData();
        if (data == null || !data.isLaserGuidanceType()) {
            return;
        }
        AbstractVehicle sourceVehicle = bullet.getShooterVehicle();
        if (sourceVehicle == null || sourceVehicle.isDestroyed()) {
            return;
        }
        Entity target = bullet.getTargetEntity();
        if (target == null || !target.isAlive()) {
            target = gunner.getTrackedTarget();
        }
        if (target == null || !target.isAlive()) {
            return;
        }
        AbstractVehicle targetVehicle = target instanceof AbstractVehicle vehicle ? vehicle
                : target.getVehicle() instanceof AbstractVehicle vehicle ? vehicle : null;
        if (targetVehicle != null) {
            warnLaserHit(sourceVehicle, targetVehicle);
        } else if (target instanceof ServerPlayer player) {
            warnLaserPlayer(sourceVehicle, player);
        }
    }

    /**
     * 照射者当前武器是否激光系（LBR/LH/SALH）。
     * 服务端权威解析（客户端会话不含武器类型）：操作手所乘载具 → 操作位武器站 → 当前武器。
     * 玩家与 AI gunner 共用同一解析；非本項武器或解析失败一律返回 false（fail-closed）。
     */
    private static boolean isLaserGuidanceOperator(LivingEntity user) {
        // 目的：SACLOS 等操作手制导与激光系共用瞄准会话，告警面在此按武器制导类型收窄
        if (!(user.getVehicle() instanceof AbstractVehicle vehicle)) {
            return false;
        }
        if (!(vehicle.getOwnOperatorUnit(user) instanceof WeaponUnit operatorUnit)) {
            return false;
        }
        return operatorUnit.getCurrentWeapon()
                .map(weapon -> weapon.getData() instanceof RVP_WeaponData data && data.isLaserGuidanceType())
                .orElse(false);
    }

    /** 被照射判定：激光点距本车 ≤10m，或本车 AABB 处于“照射载具→激光点”连线中段。 */
    private static boolean isIlluminated(AbstractVehicle vehicle, Vec3 source, Vec3 point) {
        AABB box = vehicle.getBoundingBox();
        // 方法 A：激光点距本车 10m 内
        if (box.inflate(LASER_POINT_RADIUS).contains(point.x, point.y, point.z)) {
            return true;
        }
        // 方法 B：本车 AABB 处于敌方载具到激光点连线（线段穿 AABB）
        return box.clip(source, point).isPresent();
    }

    private static void broadcastLaser(AbstractVehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CMissileTrackAlert(0, vehicle.getId(), S2CMissileTrackAlert.TYPE_LASER));
            }
        }
    }

    /**
     * 激光武器命中载具告警（2026-09-17）：`rvp:laser` 射线命中敌对载具时向其乘客发
     * {@link S2CMissileTrackAlert#TYPE_LASER} 激光照射告警——照射类武器命中即应触发被照射
     * 告警，与操作手照射会话（scanLevel）互补。友方不告警（Team 联盟判定同 scanLevel）；
     * 同一"射手车→目标车"对按 {@link #HIT_WARN_INTERVAL_TICK} 节流，由
     * {@code RVP_LaserWeapon.shoot} 在射线命中载具时调用。
     */
    public static void warnLaserHit(AbstractVehicle sourceVehicle, AbstractVehicle targetVehicle) {
        if (sourceVehicle == null || targetVehicle == null
                || targetVehicle.isDestroyed() || !targetVehicle.isAlive()
                || sourceVehicle == targetVehicle) {
            return;
        }
        if (sourceVehicle.getTeam() != null && targetVehicle.getTeam() != null
                && sourceVehicle.getTeam().isAlliedTo(targetVehicle.getTeam())) {
            return;
        }
        if (!shouldWarnPair(sourceVehicle.getId() + ":" + targetVehicle.getId(),
                sourceVehicle.level().getGameTime())) {
            return;
        }
        for (Entity passenger : targetVehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2CMissileTrackAlert(sourceVehicle.getId(), targetVehicle.getId(),
                                S2CMissileTrackAlert.TYPE_LASER));
            }
        }
    }

    /** 步行玩家被激光制导武器瞄准时的告警：客户端以 targetEntityId == 本人判定告警归属。 */
    private static void warnLaserPlayer(AbstractVehicle sourceVehicle, ServerPlayer player) {
        if (!player.isAlive()) {
            return;
        }
        if (sourceVehicle.getTeam() != null && player.getTeam() != null
                && sourceVehicle.getTeam().isAlliedTo(player.getTeam())) {
            return;
        }
        if (!shouldWarnPair(sourceVehicle.getId() + ":" + player.getId(),
                sourceVehicle.level().getGameTime())) {
            return;
        }
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new S2CMissileTrackAlert(sourceVehicle.getId(), player.getId(),
                        S2CMissileTrackAlert.TYPE_LASER));
    }

    /** 同一"射手→目标"对按 {@link #HIT_WARN_INTERVAL_TICK} 节流，返回本次是否允许发送。 */
    private static boolean shouldWarnPair(String key, long now) {
        Long last = LAST_HIT_WARN.get(key);
        if (last != null && now - last < HIT_WARN_INTERVAL_TICK) {
            return false;
        }
        LAST_HIT_WARN.put(key, now);
        return true;
    }
}
