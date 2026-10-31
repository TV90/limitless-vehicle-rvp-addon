package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** 距离、球体可见性与亮度预算；纯逻辑，不访问显卡，便于验证连射及大量爆炸。 */
public final class RVP_ScreenLightBudget {
    /** Shader 固定接收八盏光；客户端可以进一步调低上限。 */
    public static final int MAX_LIGHTS = 8;

    private RVP_ScreenLightBudget() { }

    /** 先排除无贡献和离屏光，随后优先附近光，再比较亮度与覆盖面积。 */
    public static List<RVP_ScreenLight> collect(List<RVP_ScreenLight> source, Vec3 camera,
            double maxDistance, int limit, Predicate<RVP_ScreenLight> visible) {
        // 正式绘制不传诊断回调，行为和开销与加入诊断前完全一致。
        return collect(source, camera, maxDistance, 0, limit, visible, null);
    }

    /**
     * 诊断入口：与正式入口使用同一套判定顺序，额外把每条被剔除的候选和具体数值交给调用方。
     *
     * @param source 本帧采样到的全部候选
     * @param camera 相机位置
     * @param maxDistance 客户端距离预算，单位格
     * @param limit 本帧灯数上限
     * @param visible 视锥可见性判断
     * @param diagnostics 剔除回调，null 表示不诊断
     */
    public static List<RVP_ScreenLight> collect(List<RVP_ScreenLight> source, Vec3 camera,
            double maxDistance, int limit, Predicate<RVP_ScreenLight> visible,
            BiConsumer<RVP_ScreenLight, String> diagnostics) {
        return collect(source, camera, maxDistance, 0, limit, visible, diagnostics);
    }

    /**
     * 完整入口：天空散射拥有独立距离预算，高空空爆的爆心通常远大于地面照亮的观察距离。
     *
     * @param skyDistance 天空散射专用距离预算；0 表示沿用普通预算
     * @param diagnostics 剔除回调，null 表示不诊断
     */
    public static List<RVP_ScreenLight> collect(List<RVP_ScreenLight> source, Vec3 camera,
            double maxDistance, double skyDistance, int limit, Predicate<RVP_ScreenLight> visible,
            BiConsumer<RVP_ScreenLight, String> diagnostics) {
        List<RVP_ScreenLight> candidates = new ArrayList<>(source.size());
        for (RVP_ScreenLight light : source) {
            // 调用快照的有效范围，结束的照亮不能借大半径保留远处无贡献的光晕。
            // 天空散射是方向性的全屏贡献，照亮与光晕都关掉时该光源仍必须保留。
            if (light.visibleRadius() <= 0 && light.skyIntensity() <= 0.001F) {
                report(diagnostics, light, "没有任何可见贡献（照亮半径、光晕半径与天空散射都为0）");
                continue;
            }
            double skyBudget = light.skyIntensity() > 0.001F && skyDistance > 0 ? skyDistance : 0;
            double allowed = skyBudget > 0 ? skyBudget : maxDistance + light.visibleRadius();
            double distanceSquared = light.position().distanceToSqr(camera);
            if (distanceSquared > allowed * allowed) {
                // 调用距离预算判断；空爆通常比地面爆心远得多，这里是最容易被忽略的一道门。
                report(diagnostics, light, String.format(Locale.ROOT,
                        "超出距离预算 距相机=%.1f格 允许=%.1f格（%s）",
                        Math.sqrt(distanceSquared), allowed,
                        skyBudget > 0 ? "天空专用预算=" + decimals(skyBudget)
                                : "maxDistance=" + decimals(maxDistance) + " + 可见半径=" + decimals(light.visibleRadius())));
                continue;
            }
            if (!visible.test(light)) {
                report(diagnostics, light, "离屏（球体屏幕范围与视锥不相交）");
                continue;
            }
            candidates.add(light);
        }
        List<RVP_ScreenLight> sorted = candidates.stream()
                .sorted(Comparator.<RVP_ScreenLight>comparingInt(light ->
                        light.position().distanceToSqr(camera) <= 32 * 32 ? 0 : 1)
                        .thenComparing(Comparator.comparingDouble((RVP_ScreenLight light) ->
                                score(light, camera)).reversed()))
                .toList();
        int keep = Math.max(0, Math.min(limit, MAX_LIGHTS));
        for (int index = keep; index < sorted.size(); index++) {
            // 调用八灯预算截断，说明是“名额不够”而不是参数无效。
            report(diagnostics, sorted.get(index), String.format(Locale.ROOT,
                    "八灯预算不足 名额=%d 排序第%d", keep, index + 1));
        }
        return List.copyOf(sorted.subList(0, Math.min(keep, sorted.size())));
    }

    /** 只有诊断开启时才回调，正式路径不产生额外对象。 */
    private static void report(BiConsumer<RVP_ScreenLight, String> diagnostics, RVP_ScreenLight light, String reason) {
        if (diagnostics != null) { diagnostics.accept(light, reason); }
    }

    /** 诊断文案里的距离统一保留一位小数，避免 2048.0 与 2048 混排。 */
    private static String decimals(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 用球体角面积近似屏幕覆盖，避免遥远且很弱的灯挤掉近处炮口。 */
    private static double score(RVP_ScreenLight light, Vec3 camera) {
        return Math.max(light.intensity() * light.radius() * light.radius(),
                        light.haloIntensity() * light.haloRadius() * light.haloRadius())
                / Math.max(1, light.position().distanceToSqr(camera));
    }
}
