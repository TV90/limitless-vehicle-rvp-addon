package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Gunner 本地锁/开火门调试器（{@code /rvpdebug gunnerlock on|off|status}）。
 *
 * <p>按事件记录 gunner 锁定与开火链每个门控的结果：本地雷达锁（距离 RANGE/方位扇区 YAW/
 * 箔条 CHAFF/落锁 LOCKED）、开火事务（选弹 NO_WEAPON/对空持锁纪律 AIR_DISCIPLINE/冷却
 * COOLDOWN/瞄准窗 AIM_WINDOW/落锁准备失败 LOCK_PREPARE_FAIL/发射 FIRED）、索敌 RCS 感知门
 * （SENSE）与外置中继锁状态（RELAY_*）。2026-09-16 本地锁俯仰射界门修复时引入，用于实机
 * 一线诊断"gunner 锁不上/不开火"类问题。</p>
 *
 * <p>纯客户端调试工具：引用 {@code Minecraft} 等客户端专属类，专用服务器严禁加载本类；
 * 单机环境下客户端与服务端共享 JVM，静态开关对服务端 AI 代码可见。所有调用点必须以
 * {@code FMLEnvironment.dist == Dist.CLIENT} 守卫（同 {@code GunnerBrain} 调
 * {@code RVP_GunnerDebugMonitor} 的既有纪律，防止专用服务器解析类引用）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_GunnerLockDebug {

    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS");
    /** 同一载具同一门控的最小日志间隔（tick）：GATED 门每 tick 重入，不节流会刷爆日志 */
    private static final long THROTTLE_TICKS = 20L;
    /** 索敌感知拒绝的节流：扫描期候选多，间隔放宽 */
    private static final long PERCEPTION_THROTTLE_TICKS = 40L;
    /** 中继不可用心跳间隔：持续缺中继时低频留痕即可 */
    private static final long RELAY_HEARTBEAT_TICKS = 100L;

    private static boolean enabled = false;
    private static PrintWriter writer;
    /** 节流表：载具实体id|通道|门控 → 上次写入的 game time */
    private static final Map<String, Long> LAST_LOG_TICK = new HashMap<>();
    /** 外置中继锁目标变化检测：launcher 实体 id → 最近记录的锁目标实体 id（Integer.MIN_VALUE=无锁） */
    private static final Map<Integer, Integer> LAST_RELAY_LOCK = new HashMap<>();

    private RVP_GunnerLockDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String getLogPath() {
        return "logs/rvp_gunner_lock_debug.log";
    }

    /**
     * 开关调试：开启时重建日志文件（截断，等价一次 clear），关闭时释放句柄并清空节流表。
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        LAST_LOG_TICK.clear();
        LAST_RELAY_LOCK.clear();
        closeWriter();
        if (value) {
            PrintWriter pw = getWriter();
            if (pw != null) {
                pw.println("=== gunnerlock 诊断已开启 ===");
                pw.flush();
            }
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "§e[RVP] gunnerlock 诊断已开启，写入 " + getLogPath()));
            }
        }
    }

    /**
     * 本地雷达锁（{@code RVP_GunnerRadarActions.maintainLocalLock}）门控结果。
     *
     * @param gate   RANGE/YAW/CHAFF/UNSUPPORTED/LOCKED
     * @param detail 数值明细（距离/aimRot/限位等），可为空串
     */
    public static void logLocalLock(AbstractVehicle vehicle, @Nullable Entity target,
                                    String gate, String detail) {
        if (!enabled || FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        throttledLog("LOCK", gate, vehicle, target, detail, THROTTLE_TICKS);
    }

    /**
     * 开火事务（{@code RVP_GunnerWeaponActions.engage}）门控结果。
     *
     * @param gate   NO_WEAPON/AIR_DISCIPLINE/COOLDOWN/AIM_WINDOW/LOCK_PREPARE_FAIL/FIRED
     */
    public static void logEngage(AbstractVehicle vehicle, @Nullable Entity target,
                                 String gate, String detail) {
        if (!enabled || FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        throttledLog("FIRE", gate, vehicle, target, detail, THROTTLE_TICKS);
    }

    /**
     * 索敌分角度 RCS 感知门（{@code GunnerTargeting.passesAspectPerception}）拒绝事件：
     * 按目标独立节流，记录因子/有效感知距离/实际距离，用于区分"隐身致盲"与锁链故障。
     */
    public static void logPerceptionReject(AbstractVehicle observer, AbstractVehicle target,
                                           double factor, double effective, double dist) {
        if (!enabled || FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        throttledLog("SENSE", "REJECT#" + target.getId(), observer, target,
                String.format("factor=%.2f effective=%.0f dist=%.0f", factor, effective, dist),
                PERCEPTION_THROTTLE_TICKS);
    }

    /**
     * 外置中继锁（{@code GunnerExternalRadarController.tick}）状态。
     * RELAY_LOCKED 按锁目标变化触发（换目标/从无到有才记一条）；RELAY_DOWN 走低频心跳。
     *
     * @param gate RELAY_DOWN/RELAY_UNLOCKED/RELAY_LOCKED
     */
    public static void logRelay(AbstractVehicle launcher, String gate,
                                @Nullable Entity lockTarget, String detail) {
        if (!enabled || FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        if ("RELAY_LOCKED".equals(gate)) {
            int id = lockTarget != null ? lockTarget.getId() : Integer.MIN_VALUE;
            Integer previous = LAST_RELAY_LOCK.get(launcher.getId());
            if (previous != null && previous == id) {
                // 锁目标未变化：稳态持锁不重复记录
                return;
            }
            LAST_RELAY_LOCK.put(launcher.getId(), id);
        }
        long minInterval = "RELAY_DOWN".equals(gate) ? RELAY_HEARTBEAT_TICKS : THROTTLE_TICKS;
        throttledLog("RELAY", gate, launcher, lockTarget, detail, minInterval);
    }

    /** 节流写核心：同一 key 在 minInterval 个 game tick 内只写一条。 */
    private static void throttledLog(String channel, String gate, AbstractVehicle vehicle,
                                     @Nullable Entity target, String detail, long minInterval) {
        long now = vehicle.level().getGameTime();
        String key = vehicle.getId() + "|" + channel + "|" + gate;
        Long last = LAST_LOG_TICK.get(key);
        if (last != null && now - last < minInterval) {
            return;
        }
        LAST_LOG_TICK.put(key, now);
        StringBuilder sb = new StringBuilder();
        sb.append(FMT.format(new Date())).append(" [").append(channel).append("] ")
                .append(vehicle.getVehicleId()).append('#').append(vehicle.getId())
                .append(" gate=").append(gate);
        if (target != null) {
            sb.append(" target=").append(target.getType().toShortString())
                    .append('#').append(target.getId());
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(' ').append(detail);
        }
        writeLine(sb.toString());
    }

    private static void writeLine(String line) {
        PrintWriter pw = getWriter();
        if (pw != null) {
            pw.println(line);
            pw.flush();
        }
    }

    private static PrintWriter getWriter() {
        if (writer == null) {
            try {
                File gameDir = Minecraft.getInstance().gameDirectory;
                File logFile = new File(gameDir, getLogPath());
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                writer = new PrintWriter(new FileWriter(logFile, false));
            } catch (Exception e) {
                System.err.println("[RVP_GunnerLockDebug] 创建日志失败: " + e.getMessage());
            }
        }
        return writer;
    }

    private static void closeWriter() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
