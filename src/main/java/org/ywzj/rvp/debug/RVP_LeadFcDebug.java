package org.ywzj.rvp.debug;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用弹道提前量火控（{@code rvp_ballistic_lead} / {@code rvp_rf}）统一诊断出口。
 *
 * <p>由 {@code /rvpdebug flags lead_fc on} 门控，默认关闭零开销。日志文件
 * {@code logs/rvp_lead_fc.log}。所有行带 {@code [v2]} 版本标记：复现日志中若不含该标记，
 * 说明游戏实际加载的仍是旧 jar，避免"改了代码没生效"的版本混淆。</p>
 */
public final class RVP_LeadFcDebug {

    /** 日志格式版本标记：每次调整日志结构时递增，便于确认游戏加载的构建版本。 */
    private static final String LOG_VERSION = "v2";
    private static final Path LOG_PATH = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
            .resolve("logs").resolve("rvp_lead_fc.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 节流表：键 = 实体ID + 节流桶名。 */
    private static final Map<String, Long> THROTTLE = new HashMap<>();

    private RVP_LeadFcDebug() {}

    /** 执行器入口四输入与解析档位（每站节流 2 秒）。 */
    public static void logExecutorInput(WeaponUnit station, String configuredMode,
                                        WeaponUnitData.FireControlSensorType sensorType,
                                        boolean machinegun, Object profile, Object stabilizer) {
        if (!RVP_DebugFlags.LEAD_FC.isEnabled() || station == null) {
            return;
        }
        String line = "executor_input vehicle=" + station.getVehicle().getVehicleId()
                + " station=" + station.getId()
                + " configuredMode='" + configuredMode + "'"
                + " sensor=" + sensorType
                + " machinegun=" + machinegun
                + " profile=" + profile
                + " stabilizer=" + stabilizer;
        write(station, "input", 2000L, line);
    }

    /**
     * 决策分支输出：相位（跟圈/无解不驱动/跟鼠标/拉回）、是否有解、炮口原点、实际瞄准点，
     * 以及炮塔当前朝向 vs 预瞄点要求朝向的对比（每站每相位节流 1 秒）。
     */
    public static void logDecision(WeaponUnit station, String phase, boolean hasSolution,
                                   RVP_LeadSolution solution, Vec3 aimFrom, Vec3 aimTarget) {
        if (!RVP_DebugFlags.LEAD_FC.isEnabled() || station == null) {
            return;
        }
        StringBuilder line = new StringBuilder("decision phase=").append(phase)
                .append(" hasSolution=").append(hasSolution)
                .append(" stationRot=(pitch=").append(fmt(station.getXRot()))
                .append(",yaw=").append(fmt(station.getYRot())).append(')');
        if (solution != null && aimFrom != null && aimTarget != null) {
            Vec3 dir = aimTarget.subtract(aimFrom);
            Vec2 required = VectorUtil.vecToRot(dir);
            line.append(" aimFrom=").append(fmtVec(aimFrom))
                    .append(" aimTarget=").append(fmtVec(aimTarget))
                    .append(" requiredRot=(pitch=").append(fmt(required.x))
                    .append(",yaw=").append(fmt(required.y)).append(')')
                    .append(" flightTicks=").append(fmt(solution.timeToImpact()));
        } else if (aimTarget != null) {
            line.append(" aimTarget=").append(fmtVec(aimTarget));
        }
        write(station, "decision:" + phase, 1000L, line.toString());
    }

    /** T 键切换被资格判定拒绝时输出三项原因（按键驱动不节流），定位"切稳定无效"。 */
    public static void logToggleRejected(WeaponUnit station) {
        if (!RVP_DebugFlags.LEAD_FC.isEnabled() || station == null) {
            return;
        }
        String configuredMode = station.getData() instanceof WeaponUnitDataExt ext
                ? ext.ywzj_rvp$getFireControlMode() : "<data非Ext>";
        WeaponUnitData.FireControlSensorType sensorType =
                RVP_WeaponSensorHelper.effectiveSensorType(station);
        boolean machinegun = RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(station);
        // currentPrimaryRvp 为空时补记机炮解析细节，便于区分"非RVP武器"与"kind非机炮"
        RVP_WeaponBase primary = RVP_WeaponResolveHelper.currentPrimaryRvp(station);
        String line = "toggle_rejected vehicle=" + station.getVehicle().getVehicleId()
                + " station=" + station.getId()
                + " configuredMode='" + configuredMode + "'"
                + " sensor=" + sensorType
                + " machinegun=" + machinegun
                + " primaryRvp=" + (primary != null)
                + " primaryKind=" + (primary != null ? primary.getData().getWeaponKind() : "null")
                + " ← T 键切换被资格判定拒绝（机炮/模式/传感器任一不成立）";
        write(station, "toggle_rejected", 0L, line);
    }

    /** 执行器解算/决策中途抛出的异常（含前 8 帧堆栈）；不节流，按次落盘后由调用方原样续抛。 */
    public static void logThrowable(WeaponUnit station, Throwable t) {
        if (station == null) {
            return;
        }
        StringBuilder line = new StringBuilder("executor_throwable vehicle=")
                .append(station.getVehicle().getVehicleId())
                .append(" station=").append(station.getId())
                .append(" ex=").append(t.getClass().getName())
                .append(": ").append(t.getMessage());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(8, frames.length); i++) {
            line.append(" | at ").append(frames[i]);
        }
        try {
            Files.writeString(LOG_PATH, TIME.format(LocalDateTime.now()) + " [" + LOG_VERSION + "] "
                            + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写失败静默：诊断输出不参与任何行为
        }
    }

    private static void write(WeaponUnit station, String throttleKey, long throttleMs, String body) {
        long now = System.currentTimeMillis();
        String key = station.getId() + "|" + throttleKey;
        Long last = THROTTLE.get(key);
        if (throttleMs > 0 && last != null && now - last < throttleMs) {
            return;
        }
        THROTTLE.put(key, now);
        try {
            Files.writeString(LOG_PATH, TIME.format(LocalDateTime.now()) + " [" + LOG_VERSION + "] "
                            + body + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写失败静默：诊断输出不参与任何行为
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String fmtVec(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
    }
}
