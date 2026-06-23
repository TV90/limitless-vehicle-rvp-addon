package org.ywzj.rvp.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RVP_HitboxDebug {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("hitboxdebug.log");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int INTERVAL_TICKS = 40;

    private static final AtomicInteger DATA_LOAD_COUNT = new AtomicInteger();
    private static final AtomicInteger UPDATE_ENTER_COUNT = new AtomicInteger();
    private static final AtomicInteger EMPTY_CONFIG_COUNT = new AtomicInteger();
    private static final Map<String, AtomicInteger> PART_MISSING_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> GROUP_MISSING_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> RESET_COUNTS = new ConcurrentHashMap<>();

    private static volatile String lastLoadedIds = "";
    private static volatile String lastUpdateIds = "";
    private static volatile String lastResetSummary = "";

    private RVP_HitboxDebug() {}

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static Path getLogPath() {
        return LOG_PATH;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
        if (enabled) {
            resetCounters();
        }
        appendFileLog("toggle enabled=" + enabled);
    }

    public static void clearLog() {
        try {
            Files.deleteIfExists(LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP][HitboxDebug] Failed to clear {}", LOG_PATH, e);
        }
    }

    public static void noteConfigLoaded(List<String> ids) {
        DATA_LOAD_COUNT.incrementAndGet();
        lastLoadedIds = ids == null ? "" : String.join(",", ids);
    }

    public static void noteUpdateEnter(String weaponUnitId, List<String> ids) {
        UPDATE_ENTER_COUNT.incrementAndGet();
        lastUpdateIds = weaponUnitId + ":" + (ids == null ? "" : String.join(",", ids));
    }

    public static void noteEmptyConfig(String weaponUnitId) {
        EMPTY_CONFIG_COUNT.incrementAndGet();
        lastUpdateIds = weaponUnitId + ":<empty>";
    }

    public static void notePartMissing(String partId) {
        PART_MISSING_COUNTS.computeIfAbsent(partId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public static void noteGroupMissing(String partId) {
        GROUP_MISSING_COUNTS.computeIfAbsent(partId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public static void noteReset(String partId, String before, String after) {
        RESET_COUNTS.computeIfAbsent(partId, ignored -> new AtomicInteger()).incrementAndGet();
        lastResetSummary = partId + " before=" + before + " after=" + after;
    }

    public static void dumpNow(String reason) {
        dumpVehicleSnapshot(reason, null);
    }

    public static int getIntervalTicks() {
        return INTERVAL_TICKS;
    }

    public static void dumpVehicleSnapshot(String reason, AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("reason=").append(reason).append('\n');
        sb.append("configLoads=").append(DATA_LOAD_COUNT.get())
                .append(" updateEnters=").append(UPDATE_ENTER_COUNT.get())
                .append(" emptyConfig=").append(EMPTY_CONFIG_COUNT.get()).append('\n');
        sb.append("lastLoadedIds=").append(lastLoadedIds).append('\n');
        sb.append("lastUpdateIds=").append(lastUpdateIds).append('\n');
        sb.append("lastReset=").append(lastResetSummary).append('\n');
        sb.append("partMissing=").append(formatCounts(PART_MISSING_COUNTS)).append('\n');
        sb.append("groupMissing=").append(formatCounts(GROUP_MISSING_COUNTS)).append('\n');
        sb.append("resetCounts=").append(formatCounts(RESET_COUNTS)).append('\n');

        if (vehicle == null) {
            sb.append("vehicle=<null>\n");
            appendFileLog(sb.toString());
            return;
        }

        sb.append("vehicleId=").append(vehicle.getVehicleId()).append('\n');
        vehicle.getPartUnit("turret").ifPresentOrElse(partUnit -> {
            Vec3 turretPivot = partUnit.getStructureGroup() == null ? null : partUnit.getStructureGroup().globalTransform().offset();
            sb.append(formatPart("turret", partUnit));
            if (partUnit instanceof WeaponUnit weaponUnit && weaponUnit.getData() instanceof WeaponUnitDataExt ext) {
                List<String> ids = ext.ywzj_rvp$getFollowParentOnlyPartUnitIds();
                sb.append("turret.followParentOnlyIds=").append(ids == null ? "" : String.join(",", ids)).append('\n');
                for (String id : ids) {
                    vehicle.getPartUnit(id).ifPresentOrElse(
                            child -> sb.append(formatPart(id, child, vehicle, turretPivot)),
                            () -> sb.append(id).append(".part=<missing>\n")
                    );
                }
            }
        }, () -> sb.append("turret=<missing>\n"));
        appendFileLog(sb.toString());
    }

    private static String formatPart(String label, PartUnit<?> partUnit) {
        return formatPart(label, partUnit, null, null);
    }

    private static String formatPart(String label, PartUnit<?> partUnit, AbstractVehicle vehicle, Vec3 turretPivot) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(".id=").append(partUnit.getId()).append('\n');
        sb.append(label).append(".class=").append(partUnit.getClass().getSimpleName()).append('\n');
        if (partUnit instanceof WeaponUnit weaponUnit) {
            sb.append(label).append(".weaponRot=x:")
                    .append(String.format("%.2f", weaponUnit.getXRot()))
                    .append(" y:")
                    .append(String.format("%.2f", weaponUnit.getYRot()))
                    .append('\n');
        }
        VehicleCubeGroup group = partUnit.getStructureGroup();
        if (group == null) {
            sb.append(label).append(".group=<null>\n");
            return sb.toString();
        }
        sb.append(label).append(".group.local=").append(formatEuler(group.rotation)).append('\n');
        sb.append(label).append(".group.base=").append(formatEuler(group.baseRotation)).append('\n');
        sb.append(label).append(".group.global=").append(formatEuler(group.globalTransform().rotation())).append('\n');
        sb.append(label).append(".group.pivotOffset=").append(group.pivotOffset).append('\n');
        sb.append(label).append(".group.parentPivot=").append(group.parent == null ? "<null>" : group.parent.pivot).append('\n');
        sb.append(label).append(".obbCount=").append(partUnit.getPartCubeOBBs().size()).append('\n');
        if (vehicle != null && !partUnit.getPartCubeOBBs().isEmpty()) {
            Quaternionf turretWorldRot = turretPivot == null ? null : vehicle.rotYXZ().mul(new Quaternionf(group.globalTransform().rotation()));
            for (int i = 0; i < partUnit.getPartCubeOBBs().size(); i++) {
                VehicleCubeOBB cubeOBB = partUnit.getPartCubeOBBs().get(i);
                Vec3 obbCenter = cubeOBB.position == null ? cubeOBB.center(vehicle) : cubeOBB.position;
                sb.append(label).append(".obb").append(i).append(".center=").append(obbCenter).append('\n');
                sb.append(label).append(".obb").append(i).append(".offset=").append(cubeOBB.offset()).append('\n');
                sb.append(label).append(".obb").append(i).append(".rotation=").append(formatEuler(cubeOBB.rotation == null ? cubeOBB.selfRot() : cubeOBB.rotation)).append('\n');
                sb.append(label).append(".obb").append(i).append(".rawLocalCenter=").append(new Vec3(
                        cubeOBB.x + cubeOBB.width / 2,
                        cubeOBB.y + cubeOBB.height / 2,
                        cubeOBB.z + cubeOBB.depth / 2)).append('\n');
                if (turretPivot != null && turretWorldRot != null) {
                    Vec3 fromTurretPivot = obbCenter.subtract(vehicle.position().add(turretPivot));
                    sb.append(label).append(".obb").append(i).append(".fromTurretPivot=").append(fromTurretPivot).append('\n');
                    Vector3f turretLocal = new Quaternionf(turretWorldRot).conjugate().transform(fromTurretPivot.toVector3f());
                    sb.append(label).append(".obb").append(i).append(".inTurretLocal=").append(new Vec3(turretLocal)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String formatEuler(Quaternionf rotation) {
        if (rotation == null) {
            return "<null>";
        }
        Vector3f euler = rotation.getEulerAnglesYXZ(new Vector3f());
        return String.format("x=%.3f y=%.3f z=%.3f", Math.toDegrees(euler.x), Math.toDegrees(euler.y), Math.toDegrees(euler.z));
    }

    private static String formatCounts(Map<String, AtomicInteger> counts) {
        if (counts.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, AtomicInteger> entry : counts.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue().get());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void resetCounters() {
        DATA_LOAD_COUNT.set(0);
        UPDATE_ENTER_COUNT.set(0);
        EMPTY_CONFIG_COUNT.set(0);
        PART_MISSING_COUNTS.clear();
        GROUP_MISSING_COUNTS.clear();
        RESET_COUNTS.clear();
        lastLoadedIds = "";
        lastUpdateIds = "";
        lastResetSummary = "";
    }

    private static synchronized void appendFileLog(String message) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][HitboxDebug] Failed to append debug log to {}", LOG_PATH, e);
        }
    }
}
