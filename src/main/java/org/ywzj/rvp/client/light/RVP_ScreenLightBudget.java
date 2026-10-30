package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** 距离、球体可见性与亮度预算；纯逻辑，不访问显卡，便于验证连射及大量爆炸。 */
public final class RVP_ScreenLightBudget {
    /** Shader 固定接收八盏光；客户端可以进一步调低上限。 */
    public static final int MAX_LIGHTS = 8;

    private RVP_ScreenLightBudget() { }

    /** 先排除无贡献和离屏光，随后优先附近光，再比较亮度与覆盖面积。 */
    public static List<RVP_ScreenLight> collect(List<RVP_ScreenLight> source, Vec3 camera,
            double maxDistance, int limit, Predicate<RVP_ScreenLight> visible) {
        // 调用快照的有效范围，结束的照亮不能借大半径保留远处无贡献的光晕。
        return source.stream().filter(light -> light.visibleRadius() > 0)
                .filter(light -> light.position().distanceToSqr(camera)
                        <= Math.pow(maxDistance + light.visibleRadius(), 2))
                .filter(visible)
                .sorted(Comparator.<RVP_ScreenLight>comparingInt(light ->
                        light.position().distanceToSqr(camera) <= 32 * 32 ? 0 : 1)
                        .thenComparing(Comparator.comparingDouble((RVP_ScreenLight light) ->
                                score(light, camera)).reversed()))
                .limit(Math.max(0, Math.min(limit, MAX_LIGHTS))).toList();
    }

    /** 用球体角面积近似屏幕覆盖，避免遥远且很弱的灯挤掉近处炮口。 */
    private static double score(RVP_ScreenLight light, Vec3 camera) {
        return Math.max(light.intensity() * light.radius() * light.radius(),
                        light.haloIntensity() * light.haloRadius() * light.haloRadius())
                / Math.max(1, light.position().distanceToSqr(camera));
    }
}
