package org.ywzj.rvp.vehicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 骨块级被动电子战防御措施配置（{@code bone_modules} 骨块条目的 {@code ecm_passive} 子对象）。
 *
 * <p>被敌对雷达照射时在载具周围生成假目标诱饵（距离分档：远档多而散、近档少而集中），
 * 照射源拉近到烧穿距离内后假目标失效。激活期结束进入充能期，冷却期间再次照射不生成。</p>
 *
 * @param activeDurationTicks  激活时长（tick）（默认 140 = 7 秒）
 * @param cooldownTicks        充能时长（tick）（默认 300 = 15 秒）
 * @param burnThroughDistance  烧穿距离（格）：照射源水平距离小于该值时烧穿清场并进入充能（默认 250）
 * @param nctrNames            NCTR 假标识池：每枚假目标生成时随机抽取一个机型名，
 *                             在敌雷达触点/战术地图显示（默认 F15/S27/J10/B52/F16/RAF/H6K/F18/F14）
 * @param decoySpeedMin        假目标漂移速度下限（block/tick）（默认 0.8 ≈ 58 km/h）
 * @param decoySpeedMax        假目标漂移速度上限（block/tick）（默认 1.5 ≈ 108 km/h）
 * @param bands                距离分档（按 max_distance 降序匹配：首个满足
 *                             {@code 水平距离 ≤ max_distance} 的档位生效；全部不满足为最远档）
 */
public record BoneEcmPassiveConfig(
        int activeDurationTicks,
        int cooldownTicks,
        double burnThroughDistance,
        List<String> nctrNames,
        double decoySpeedMin,
        double decoySpeedMax,
        List<Band> bands
) {

    /** 单个距离档：覆盖上限距离 + 假目标数量 + 散布半径。 */
    public record Band(double maxDistance, int decoyCount, double radius) {
    }

    /** 默认 NCTR 假标识池（用户指定）。 */
    private static final String[] DEFAULT_NCTR_NAMES = {
            "F15", "S27", "J10", "B52", "F16", "RAF", "H6K", "F18", "F14"
    };

    public BoneEcmPassiveConfig {
        activeDurationTicks = Math.max(1, activeDurationTicks);
        cooldownTicks = Math.max(0, cooldownTicks);
        burnThroughDistance = Math.max(0.0, burnThroughDistance);
        nctrNames = nctrNames == null || nctrNames.isEmpty()
                ? List.of(DEFAULT_NCTR_NAMES)
                : List.copyOf(nctrNames);
        decoySpeedMin = Math.max(0.0, decoySpeedMin);
        decoySpeedMax = Math.max(decoySpeedMin, decoySpeedMax);
        bands = bands == null || bands.isEmpty() ? defaultBands() : List.copyOf(bands);
    }

    /** 默认分档（沿用旧版 EwVehicleConfig.defaults() 数值）。 */
    public static List<Band> defaultBands() {
        return List.of(
                new Band(1500.0, 6, 300.0),
                new Band(1000.0, 4, 200.0),
                new Band(500.0, 3, 150.0),
                new Band(250.0, 2, 100.0)
        );
    }

    /** 随机取一个 NCTR 假标识名；池为空时返回空串。 */
    public String randomNctrName(net.minecraft.util.RandomSource random) {
        if (nctrNames.isEmpty()) {
            return "";
        }
        return nctrNames.get(random.nextInt(nctrNames.size()));
    }

    /**
     * 按照射源水平距离解析距离档（取"最近"的满足档：dist 越大档越远，越小越近）。
     * 遍历取最后一个满足 {@code dist ≤ maxDistance} 的档；全部不满足（dist 超过最远档）
     * 时取第一档（最远档）。调用方应先按 {@link #burnThroughDistance()} 判烧穿。
     */
    public @Nullable Band resolveBand(double horizontalDistance) {
        Band nearest = null;
        for (Band band : bands) {
            if (horizontalDistance <= band.maxDistance()) {
                nearest = band;
            }
        }
        return nearest == null && !bands.isEmpty() ? bands.get(0) : nearest;
    }

    public static @Nullable BoneEcmPassiveConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        int activeTicks = GsonHelper.getAsInt(obj, "active_duration_ticks", 140);
        int cooldownTicks = GsonHelper.getAsInt(obj, "cooldown_ticks", 300);
        double burnThrough = GsonHelper.getAsDouble(obj, "burn_through_distance", 250.0);

        List<String> nctr = new ArrayList<>();
        JsonArray nctrArr = GsonHelper.getAsJsonArray(obj, "nctr_names", null);
        if (nctrArr != null) {
            for (JsonElement e : nctrArr) {
                if (e != null && !e.getAsString().isBlank()) {
                    nctr.add(e.getAsString().trim());
                }
            }
        }

        double speedMin = GsonHelper.getAsDouble(obj, "decoy_speed_min", 0.8);
        double speedMax = GsonHelper.getAsDouble(obj, "decoy_speed_max", 1.5);

        List<Band> bands = new ArrayList<>();
        JsonArray bandArr = GsonHelper.getAsJsonArray(obj, "bands", null);
        if (bandArr != null) {
            for (JsonElement e : bandArr) {
                if (e == null || !e.isJsonObject()) {
                    continue;
                }
                JsonObject b = e.getAsJsonObject();
                double maxDistance = GsonHelper.getAsDouble(b, "max_distance", 500.0);
                int count = GsonHelper.getAsInt(b, "decoy_count", 3);
                double radius = GsonHelper.getAsDouble(b, "radius", 150.0);
                if (count > 0 && radius > 0.0) {
                    bands.add(new Band(Math.max(0.0, maxDistance), count, radius));
                }
            }
        }
        return new BoneEcmPassiveConfig(activeTicks, cooldownTicks, burnThrough,
                nctr, speedMin, speedMax, bands);
    }
}
