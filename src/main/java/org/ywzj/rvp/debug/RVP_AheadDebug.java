package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.ahead.RVP_AheadSolution;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionPayloadData;
import org.ywzj.rvp.weapon.data.RVP_SubmunitionReleaseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RVP_AheadDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("rvp_ahead_debug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_AheadDebug() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        appendFileLog("toggle enabled=" + enabled);
    }

    public static void logProgram(RVP_WeaponData data, RVP_AheadSolution solution) {
        if (!isEnabled() || data == null || !data.isAheadEnabled()) {
            return;
        }
        ResourceLocation weaponId = data.getWeaponId();
        if (solution == null) {
            LOGGER.info("[RVP][AHEAD] program weapon={} result=null", weaponId);
            appendFileLog("[RVP][AHEAD] program weapon=" + weaponId + " result=null");
            return;
        }
        String line = String.format(
                "[RVP][AHEAD] program weapon=%s valid=%s programmed=%dm reference=%.2fm lead=%s reason=%s",
                weaponId,
                solution.isValid(),
                solution.programmedDistanceMeters(),
                solution.referenceDistanceMeters(),
                solution.usedLeadSolution(),
                solution.invalidReason()
        );
        LOGGER.info(
                "[RVP][AHEAD] program weapon={} valid={} programmed={}m reference={}m lead={} reason={}",
                weaponId,
                solution.isValid(),
                solution.programmedDistanceMeters(),
                String.format("%.2f", solution.referenceDistanceMeters()),
                solution.usedLeadSolution(),
                solution.invalidReason()
        );
        appendFileLog(line);
    }

    public static void logFuseRelease(RVP_BaseBullet parent, RVP_SubmunitionReleaseData release,
                                      RVP_EnumSubmunitionTrigger trigger, int eventsThisTick, int spawned) {
        if (!isEnabled() || parent == null || release == null || trigger != RVP_EnumSubmunitionTrigger.ON_FUSE) {
            return;
        }
        RVP_WeaponData data = parent.getRvpData();
        if (data == null || !data.isAheadEnabled()) {
            return;
        }
        int configuredPerEvent = 0;
        StringBuilder payloads = new StringBuilder();
        for (RVP_SubmunitionPayloadData payload : release.getPayloads()) {
            configuredPerEvent += payload.getCount();
            if (payloads.length() > 0) {
                payloads.append(",");
            }
            payloads.append(payload.resolveWeaponId(parent.getWeaponId()));
        }
        String line = String.format(
                "[RVP][AHEAD] fuse weapon=%s entityId=%d airburst=%dm events=%d configured_per_event=%d spawned=%d payloads=%s pos=(%.2f,%.2f,%.2f)",
                parent.getWeaponId(),
                parent.getId(),
                parent.getProgrammedAirburstDistance(),
                eventsThisTick,
                configuredPerEvent,
                spawned,
                payloads,
                parent.getX(),
                parent.getY(),
                parent.getZ()
        );
        LOGGER.info(
                "[RVP][AHEAD] fuse weapon={} entityId={} airburst={}m events={} configured_per_event={} spawned={} payloads={} pos=({},{},{})",
                parent.getWeaponId(),
                parent.getId(),
                parent.getProgrammedAirburstDistance(),
                eventsThisTick,
                configuredPerEvent,
                spawned,
                payloads,
                String.format("%.2f", parent.getX()),
                String.format("%.2f", parent.getY()),
                String.format("%.2f", parent.getZ())
        );
        appendFileLog(line);
    }

    private static synchronized void appendFileLog(String message) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][AHEAD] Failed to append debug log to {}", LOG_PATH, e);
        }
    }
}
