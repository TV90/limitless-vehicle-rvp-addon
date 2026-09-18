package org.ywzj.rvp.radar;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 观瞄/雷达弹药探测诊断（纯客户端，仿 {@code RVP_GunnerLockDebug} 纪律）：
 * phase 雷达扫描期对 {@code allEntities} 中的 RVP 弹药逐个记录
 * 「本地实体 or 广播克隆 / 距离 / 分角度因子 / 是否过 maxScan 门」，
 * 用于定位 BVR（超视距）弹药探测回归的断链位置（2026-09-19 排查工具）。
 *
 * <p>默认关闭、零开销；调用点在 {@code RVP_ClientRadarTickHandler}（纯客户端类）。
 * 专用日志 {@code logs/rvp_radar_ammo_debug.log}。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_RadarAmmoDebug {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static boolean enabled;
    private static PrintWriter writer;

    private RVP_RadarAmmoDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String getLogPath() {
        return "logs/rvp_radar_ammo_debug.log";
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        closeWriter();
        if (value) {
            PrintWriter pw = getWriter();
            if (pw != null) {
                pw.println("=== radarammo 诊断已开启 ===");
                pw.flush();
            }
            LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§e[RVP] radarammo 诊断已开启，写入 " + getLogPath()));
            }
        }
    }

    /** phase 雷达扫描完成后的快照记录：本地/克隆弹药计数 + 每弹距离/因子/过门结果。 */
    public static void logScan(AbstractVehicle vehicle, String radarId, double maxScanDistance,
                               java.lang.Iterable<Entity> allEntities, java.util.Set<Integer> localIds,
                               java.util.Set<Integer> detectedIds) {
        if (!enabled || net.minecraftforge.fml.loading.FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        PrintWriter pw = getWriter();
        if (pw == null) {
            return;
        }
        int localAmmo = 0;
        int cloneAmmo = 0;
        StringBuilder detail = new StringBuilder();
        for (Entity entity : allEntities) {
            if (!(entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            boolean local = localIds.contains(entity.getId());
            if (local) {
                localAmmo++;
            } else {
                cloneAmmo++;
            }
            double dist = Math.sqrt(entity.position().distanceToSqr(
                    vehicle.position().x, vehicle.position().y, vehicle.position().z));
            float sig = bullet.getRadarSignatureTowards(
                    new net.minecraft.world.phys.Vec3(vehicle.position().x, vehicle.position().y, vehicle.position().z));
            String tag = local ? "local" : "clone";
            detail.append("\n  ").append(tag).append(" id=").append(entity.getId())
                    .append(" kind=").append(bullet.getWeaponKind())
                    .append(" dist=").append(String.format("%.0f", dist))
                    .append(" sig=").append(String.format("%.3f", sig))
                    .append(" detectable=").append(bullet.isRadarDetectableAmmo())
                    .append(" pass=").append(detectedIds.contains(entity.getId()));
        }
        pw.println(FMT.format(new Date()) + " radar=" + radarId
                + " maxScan=" + (int) maxScanDistance
                + " ammo(local/clone)=" + localAmmo + "/" + cloneAmmo
                + " detected=" + detectedIds.size()
                + detail);
        pw.flush();
    }

    private static synchronized PrintWriter getWriter() {
        if (writer == null) {
            try {
                writer = new PrintWriter(new java.io.FileWriter(getLogPath(), true));
            } catch (Exception ignored) {
            }
        }
        return writer;
    }

    private static synchronized void closeWriter() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
