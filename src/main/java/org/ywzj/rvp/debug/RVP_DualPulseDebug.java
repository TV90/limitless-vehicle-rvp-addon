package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RVP_DualPulseDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("dualpulsedebug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_HISTORY_LINES = 80;

    private static final Map<Integer, MissileTrace> TRACES = new ConcurrentHashMap<>();

    private RVP_DualPulseDebug() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        if (enabled) {
            TRACES.clear();
        }
        appendFileLog("toggle enabled=" + enabled);
    }

    public static void clearLog() {
        TRACES.clear();
        try {
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP][DualPulseDebug] Failed to clear {}", LOG_PATH, e);
        }
    }

    public static void noteEvaluation(RVP_BaseBullet projectile,
                                      RVP_WeaponData data,
                                      Vec3 velocity,
                                      int ignition,
                                      int motorTick,
                                      float burn1,
                                      boolean speedEnabled,
                                      float speedThreshold,
                                      boolean speedOk,
                                      boolean distEnabled,
                                      float distThreshold,
                                      boolean distOk,
                                      @Nullable Entity targetEntity,
                                      @Nullable Vec3 targetPos,
                                      @Nullable Vec3 lastGuidancePos,
                                      double resolvedDistance,
                                      @Nullable String distanceSource) {
        if (!ENABLED.get() || projectile.level().isClientSide()) {
            return;
        }
        MissileTrace trace = TRACES.computeIfAbsent(projectile.getId(), ignored -> new MissileTrace(projectile.getId()));
        trace.weaponId = data.getWeaponId();
        trace.tickCount = projectile.tickCount;
        int secondPulseStartTick = projectile.getSecondPulseStartTick();
        trace.secondPulseStartTick = secondPulseStartTick;

        boolean shouldLog = projectile.tickCount % 5 == 0
                || speedOk
                || distOk
                || secondPulseStartTick >= 0
                || trace.lastLoggedTick == Integer.MIN_VALUE
                || trace.lastSpeedOk != speedOk
                || trace.lastDistOk != distOk;
        if (!shouldLog) {
            trace.lastSpeedOk = speedOk;
            trace.lastDistOk = distOk;
            return;
        }

        String line = "missileId=" + projectile.getId()
                + " weapon=" + safeId(data.getWeaponId())
                + " tick=" + projectile.tickCount
                + " ignition=" + ignition
                + " motorTick=" + motorTick
                + " burn1=" + trimFloat(burn1)
                + " secondStart=" + secondPulseStartTick
                + " speed=" + trimDouble(velocity.length())
                + " speedThreshold=" + trimFloat(speedThreshold)
                + " speedEnabled=" + speedEnabled
                + " speedOk=" + speedOk
                + " distThreshold=" + trimFloat(distThreshold)
                + " distEnabled=" + distEnabled
                + " distOk=" + distOk
                + " distance=" + (resolvedDistance >= 0 ? trimDouble(resolvedDistance) : "<none>")
                + " distanceSource=" + (distanceSource == null ? "<none>" : distanceSource)
                + " targetEntity=" + formatTarget(targetEntity)
                + " targetPos=" + formatVec(targetPos)
                + " lastGuidancePos=" + formatVec(lastGuidancePos)
                + " pos=" + formatVec(projectile.position());
        trace.append(line);
        appendFileLog(line);
        trace.lastLoggedTick = projectile.tickCount;
        trace.lastSpeedOk = speedOk;
        trace.lastDistOk = distOk;
    }

    public static void noteSecondPulseStarted(RVP_BaseBullet projectile, RVP_WeaponData data) {
        if (!ENABLED.get() || projectile.level().isClientSide()) {
            return;
        }
        MissileTrace trace = TRACES.computeIfAbsent(projectile.getId(), ignored -> new MissileTrace(projectile.getId()));
        trace.weaponId = data.getWeaponId();
        trace.tickCount = projectile.tickCount;
        int secondPulseStartTick = projectile.getSecondPulseStartTick();
        trace.secondPulseStartTick = secondPulseStartTick;
        String line = "missileId=" + projectile.getId()
                + " weapon=" + safeId(data.getWeaponId())
                + " event=second_pulse_started"
                + " tick=" + projectile.tickCount
                + " secondStart=" + secondPulseStartTick
                + " pos=" + formatVec(projectile.position());
        trace.append(line);
        appendFileLog(line);
    }

    public static String dumpSnapshot(@Nullable ServerPlayer player) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RVP Dual Pulse Debug ===\n");
        sb.append("enabled=").append(ENABLED.get()).append('\n');
        sb.append("logPath=").append(LOG_PATH).append('\n');
        if (player != null) {
            sb.append("player=").append(player.getName().getString()).append('\n');
            sb.append("level=").append(player.level().dimension().location()).append('\n');
        }
        List<MissileTrace> traces = new ArrayList<>(TRACES.values());
        traces.sort(Comparator.comparingInt(trace -> trace.missileId));
        sb.append("traceCount=").append(traces.size()).append('\n');
        for (MissileTrace trace : traces) {
            sb.append("--- missile ").append(trace.missileId).append(" ---\n");
            sb.append("weapon=").append(safeId(trace.weaponId))
                    .append(" lastTick=").append(trace.tickCount)
                    .append(" secondStart=").append(trace.secondPulseStartTick)
                    .append('\n');
            for (String line : trace.history) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendFileLog(String message) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][DualPulseDebug] Failed to append debug log to {}", LOG_PATH, e);
        }
    }

    private static String safeId(@Nullable ResourceLocation id) {
        return id == null ? "<null>" : id.toString();
    }

    private static String formatTarget(@Nullable Entity entity) {
        if (entity == null) {
            return "<null>";
        }
        ResourceLocation typeId = entity.getType().builtInRegistryHolder().key().location();
        return typeId + "#" + entity.getId();
    }

    private static String formatVec(@Nullable Vec3 vec) {
        if (vec == null) {
            return "<null>";
        }
        return "(" + trimDouble(vec.x) + "," + trimDouble(vec.y) + "," + trimDouble(vec.z) + ")";
    }

    private static String trimDouble(double value) {
        return String.format("%.3f", value);
    }

    private static String trimFloat(float value) {
        return String.format("%.3f", value);
    }

    private static final class MissileTrace {
        private final int missileId;
        private final Deque<String> history = new ArrayDeque<>();
        private ResourceLocation weaponId;
        private int tickCount;
        private int secondPulseStartTick = -1;
        private int lastLoggedTick = Integer.MIN_VALUE;
        private boolean lastSpeedOk;
        private boolean lastDistOk;

        private MissileTrace(int missileId) {
            this.missileId = missileId;
        }

        private void append(String line) {
            if (history.size() >= MAX_HISTORY_LINES) {
                history.removeFirst();
            }
            history.addLast(line);
        }
    }
}
