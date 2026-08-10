package org.ywzj.rvp.debug;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.countermeasure.RVP_JammingRuntime;
import org.ywzj.rvp.vehicle.BoneApsConfig;
import org.ywzj.rvp.vehicle.BoneJammerConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 探测范围可视化（/rvpdebug scanviz）。
 *
 * <p>对开启调试的玩家所在载具，低频（每 {@link #COVERAGE_REFRESH_INTERVAL} tick）向其定向发送粒子，
 * 勾勒出各设备的锥形探测/干扰范围轮廓：
 * <ul>
 *   <li>干扰机干扰锥（{@link BoneJammerConfig}）：{@code resolveFacing} 前向 + {@code fov/2} 半角 + {@code range}，
 *       粒子类型 {@code FLAME}（黄色）；</li>
 *   <li>APS 扫描锥（{@link BoneApsConfig}）：{@code resolveFacing} 前向 + {@code scan_fov/2} 半角 + {@code detect_radius}，
 *       粒子类型 {@code SOUL_FIRE_FLAME}（蓝色）。</li>
 * </ul>
 * 粒子直接以 {@link ClientboundLevelParticlesPacket} 发送给指定玩家，不广播。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_CoverageDebug {

    /** 锥体轮廓刷新间隔（tick），0.5 秒左右刷新一次保证轮廓连续。 */
    private static final long COVERAGE_REFRESH_INTERVAL = 10L;

    private static final Set<UUID> ENABLED = new HashSet<>();

    private RVP_CoverageDebug() {
    }

    /* ==================== 开关侧表 ==================== */

    public static void setEnabled(UUID playerId, boolean enabled) {
        if (enabled) {
            ENABLED.add(playerId);
        } else {
            ENABLED.remove(playerId);
        }
    }

    public static boolean isEnabled(UUID playerId) {
        return ENABLED.contains(playerId);
    }

    public static int enabledCount() {
        return ENABLED.size();
    }

    /* ==================== 服务端 tick ==================== */

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ENABLED.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (UUID playerId : new ArrayList<>(ENABLED)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !player.isAlive()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (level.getGameTime() % COVERAGE_REFRESH_INTERVAL != 0) {
                continue;
            }
            // 绘制玩家周围所有配置了探测/干扰设备的载具锥体，下车后也可远观
            for (AbstractVehicle vehicle : level.getEntitiesOfClass(
                    AbstractVehicle.class, player.getBoundingBox().inflate(512.0), v -> true)) {
                drawVehicleCoverage(level, player, vehicle);
            }
        }
    }

    /* ==================== 锥体绘制 ==================== */

    private static void drawVehicleCoverage(ServerLevel level, ServerPlayer player, AbstractVehicle vehicle) {
        Vec3 origin = vehicle.getBoundingBox().getCenter();

        Map<String, BoneJammerConfig> jammers = RVP_VehicleHitboxFactorManager.INSTANCE.resolveJammerDevices(vehicle);
        if (jammers != null) {
            for (Map.Entry<String, BoneJammerConfig> entry : jammers.entrySet()) {
                // 骨块失效（干扰机被击毁）后不再显示干扰锥
                if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), entry.getKey(), BoneModuleType.JAMMER)) {
                    continue;
                }
                BoneJammerConfig cfg = entry.getValue();
                Vec3 facing = RVP_JammingRuntime.resolveFacing(vehicle, cfg.facingPart(), cfg.facingYawDeg());
                drawCone(level, player, origin, facing, cfg.halfAngleDeg(), cfg.range(), ParticleTypes.FLAME);
            }
        }

        Map<String, BoneApsConfig> aps = RVP_VehicleHitboxFactorManager.INSTANCE.resolveApsDevices(vehicle);
        if (aps != null) {
            for (Map.Entry<String, BoneApsConfig> entry : aps.entrySet()) {
                // 传感器被击毁（APS 模块失效）后不再显示扫描锥
                if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), entry.getKey(), BoneModuleType.APS)) {
                    continue;
                }
                BoneApsConfig cfg = entry.getValue();
                Vec3 facing = RVP_JammingRuntime.resolveFacing(vehicle, cfg.facingPart(), cfg.facingYawDeg());
                drawCone(level, player, origin, facing, cfg.halfAngleDeg(), cfg.detectRadius(), ParticleTypes.SOUL_FIRE_FLAME);
            }
        }
    }

    /**
     * 在「顶点 {@code origin}、前向 {@code facing}、半角、距离」定义的圆锥侧面轮廓上撒粒子：
     * 底面圆周若干点 + 顶点到圆周的母线插值点。圆周起始角随游戏时间缓慢递增，形成流动轮廓。
     */
    private static void drawCone(ServerLevel level, ServerPlayer player, Vec3 origin, Vec3 facing,
                                 double halfAngleDeg, double range, ParticleOptions particle) {
        if (range <= 0 || origin == null || facing == null || facing.lengthSqr() <= 1.0E-8) {
            return;
        }
        // 半角接近 90° 时底面半径趋于无穷，钳制避免画出巨型轮廓
        double halfAngle = Math.toRadians(Math.min(halfAngleDeg, 89.0));
        double baseRadius = range * Math.tan(halfAngle);

        Vec3 f = facing.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 u = Math.abs(f.y) > 0.95
                ? f.cross(new Vec3(1, 0, 0)).normalize()
                : f.cross(up).normalize();
        Vec3 v = f.cross(u).normalize();
        Vec3 baseCenter = origin.add(f.scale(range));

        // 粒子密度按轮廓间距自适应（低频刷新，但每次刷新必须连成可见轮廓线）：
        // 底面圆周每 8 格一个点，母线每 3 格一个点，短距离锥体因此更密。
        int ringPoints = (int) Math.max(24, Math.min(64, Math.round(baseRadius * 2 * Math.PI / 8.0)));
        int midPoints = (int) Math.max(8, Math.min(48, Math.round(range / 3.0)));
        double angleOffset = level.getGameTime() * 0.05;

        for (int i = 0; i < ringPoints; i++) {
            double a = angleOffset + Math.PI * 2.0 * i / ringPoints;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            Vec3 ringPoint = baseCenter.add(u.scale(baseRadius * cos)).add(v.scale(baseRadius * sin));
            // 母线：顶点 → 圆周点，插值若干粒子
            for (int t = 1; t <= midPoints; t++) {
                double frac = t / (double) midPoints;
                spawnParticle(player, particle, origin.add(ringPoint.subtract(origin).scale(frac)));
            }
            spawnParticle(player, particle, ringPoint);
        }
    }

    private static void spawnParticle(ServerPlayer player, ParticleOptions type, Vec3 pos) {
        player.connection.send(new ClientboundLevelParticlesPacket(
                type, true, pos.x, pos.y, pos.z, 0f, 0f, 0f, 0f, 1));
    }
}
