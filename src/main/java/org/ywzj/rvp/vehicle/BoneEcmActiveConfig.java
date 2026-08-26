package org.ywzj.rvp.vehicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 骨块级主动电子战（ECM_ACTIVE）配置（{@code bone_modules} 骨块条目的 {@code ecm_active} 子对象）。
 *
 * <p>按键触发后进入持续干扰状态（active → cooldown），期间对范围内导弹/载具施加干扰，
 * 并一次性生成假目标。双半径分离：弹药干扰半径与载具干扰半径独立配置。</p>
 *
 * @param activeDurationTicks      主动干扰持续时长（tick）（默认 200 = 10 秒）
 * @param cooldownTicks            冷却时长（tick）（默认 600 = 30 秒）
 * @param decoyCount               释放时生成的假目标数量（默认 6）
 * @param decoyLifetimeTicks       假目标存活时长（tick）（默认 200 = 10 秒）
 * @param ammoJamRadius            弹药干扰半径（格）（默认 300）
 * @param vehicleJamRadius         载具干扰半径（格）（默认 400）
 * @param gpsOffsetMeters          GPS 导弹/炸弹落点随机偏移半径（米）（默认 20）
 * @param fakeLockMin              RWR 伪造锁定源数量下限（默认 5）
 * @param fakeLockMax              RWR 伪造锁定源数量上限（默认 10）
 * @param fakeLockDurationTicks    伪造锁定持续时长（tick）（默认 120 = 6 秒）
 * @param fakeLockSources          伪造锁定 radartype 文案池（默认 S400/J16/F18 等 10 种）
 * @param radarUnlock              是否对被干扰载具执行雷达脱锁（默认 true）
 * @param armPriorityTicks         反辐射导弹高优先级窗口时长（tick）（默认 100 = 5 秒）
 * @param armMemoryJitterMeters    反辐射导弹记忆落点随机抖动半径（米）（默认 7）
 * @param nctrNames                假目标 NCTR 假标识池（默认沿用被动ECM池，可单独覆盖）
 */
public record BoneEcmActiveConfig(
        int activeDurationTicks,
        int cooldownTicks,
        int decoyCount,
        int decoyLifetimeTicks,
        double ammoJamRadius,
        double vehicleJamRadius,
        double gpsOffsetMeters,
        int fakeLockMin,
        int fakeLockMax,
        int fakeLockDurationTicks,
        List<String> fakeLockSources,
        boolean radarUnlock,
        int armPriorityTicks,
        double armMemoryJitterMeters,
        List<String> nctrNames
) {

    /** 默认伪造锁定 radartype 文案池（设计文档 §4）。 */
    private static final String[] DEFAULT_FAKE_LOCK_SOURCES = {
            "S400", "J16", "F18", "J20", "SLM", "F15", "S57", "S35", "F22", "ITO"
    };

    /** 默认 NCTR 假标识池（与被动ECM一致，可覆盖）。 */
    private static final String[] DEFAULT_NCTR_NAMES = {
            "F15", "S27", "J10", "B52", "F16", "RAF", "H6K", "F18", "F14"
    };

    /** 假目标散布半径硬编码（格）（批示：不设配置，固定值）。 */
    public static final double DECOY_RADIUS_HARDCODED = 150.0;

    public BoneEcmActiveConfig {
        activeDurationTicks = Math.max(1, activeDurationTicks);
        cooldownTicks = Math.max(0, cooldownTicks);
        decoyCount = Math.max(0, Math.min(20, decoyCount));
        decoyLifetimeTicks = Math.max(1, decoyLifetimeTicks);
        ammoJamRadius = Math.max(0.0, ammoJamRadius);
        vehicleJamRadius = Math.max(0.0, vehicleJamRadius);
        gpsOffsetMeters = Math.max(0.0, gpsOffsetMeters);
        fakeLockMin = Math.max(1, fakeLockMin);
        fakeLockMax = Math.max(fakeLockMin, fakeLockMax);
        fakeLockDurationTicks = Math.max(1, fakeLockDurationTicks);
        fakeLockSources = fakeLockSources == null || fakeLockSources.isEmpty()
                ? List.of(DEFAULT_FAKE_LOCK_SOURCES)
                : List.copyOf(fakeLockSources);
        armPriorityTicks = Math.max(0, armPriorityTicks);
        armMemoryJitterMeters = Math.max(0.0, armMemoryJitterMeters);
        nctrNames = nctrNames == null || nctrNames.isEmpty()
                ? List.of(DEFAULT_NCTR_NAMES)
                : List.copyOf(nctrNames);
    }

    /** 随机取一个 NCTR 假标识名。 */
    public String randomNctrName(net.minecraft.util.RandomSource random) {
        if (nctrNames.isEmpty()) {
            return "";
        }
        return nctrNames.get(random.nextInt(nctrNames.size()));
    }

    public static @Nullable BoneEcmActiveConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        int activeTicks = GsonHelper.getAsInt(obj, "active_duration_ticks", 200);
        int cooldownTicks = GsonHelper.getAsInt(obj, "cooldown_ticks", 600);
        int decoyCount = GsonHelper.getAsInt(obj, "decoy_count", 6);
        int decoyLifetime = GsonHelper.getAsInt(obj, "decoy_lifetime_ticks", 200);
        double ammoRadius = GsonHelper.getAsDouble(obj, "ammo_jam_radius", 300.0);
        double vehicleRadius = GsonHelper.getAsDouble(obj, "vehicle_jam_radius", 400.0);
        double gpsOffset = GsonHelper.getAsDouble(obj, "gps_offset_meters", 20.0);
        int fakeMin = GsonHelper.getAsInt(obj, "fake_lock_min", 5);
        int fakeMax = GsonHelper.getAsInt(obj, "fake_lock_max", 10);
        int fakeDuration = GsonHelper.getAsInt(obj, "fake_lock_duration_ticks", 120);
        boolean radarUnlock = GsonHelper.getAsBoolean(obj, "radar_unlock", true);
        int armPriority = GsonHelper.getAsInt(obj, "arm_priority_ticks", 100);
        double armJitter = GsonHelper.getAsDouble(obj, "arm_memory_jitter_meters", 7.0);

        // 解析伪造锁定源池
        List<String> sources = new ArrayList<>();
        JsonArray srcArr = GsonHelper.getAsJsonArray(obj, "fake_lock_sources", null);
        if (srcArr != null) {
            for (JsonElement e : srcArr) {
                if (e != null && e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    sources.add(e.getAsString().trim());
                }
            }
        }

        // 解析 NCTR 假标识池（可选覆盖，键名兼容 nctr_names / fake_decoy_nctr）
        List<String> nctr = new ArrayList<>();
        JsonArray nctrArr = GsonHelper.getAsJsonArray(obj, "nctr_names", null);
        if (nctrArr == null) {
            nctrArr = GsonHelper.getAsJsonArray(obj, "fake_decoy_nctr", null);
        }
        if (nctrArr != null) {
            for (JsonElement e : nctrArr) {
                if (e != null && e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    nctr.add(e.getAsString().trim());
                }
            }
        }

        return new BoneEcmActiveConfig(activeTicks, cooldownTicks, decoyCount, decoyLifetime,
                ammoRadius, vehicleRadius, gpsOffset,
                fakeMin, fakeMax, fakeDuration,
                sources, radarUnlock, armPriority, armJitter, nctr);
    }
}
