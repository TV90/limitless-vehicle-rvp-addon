package org.ywzj.rvp.light;

/**
 * 由 Java 代码统一生成的不可变光源参数，不读取武器 JSON 光配置，不引用客户端类。
 * @param enabled 是否启用
 * @param red 红色，0～1
 * @param green 绿色，0～1
 * @param blue 蓝色，0～1
 * @param radius 影响半径，单位格，最大 48
 * @param intensity 亮度倍率，0～4
 * @param durationTicks 闪光寿命，单位 tick，1～40
 * @param mergeWindowTicks 同源短光合并窗口，单位 tick，0～10
 * @param surfaceWeight 表面受光倍率，0～1
 * @param decay 衰减指数，0.25～8
 * @param bloom 是否允许亮部形成光晕
 * @param volumetricStrength 空气散射强度，0～1，0 关闭可选雾光
 * @param blockLightLevel P5 预留字段；当前只验证屏幕空间光，所有代码预设固定为 0
 */
public record RVP_LightParameters(boolean enabled, float red, float green, float blue,
        float radius, float intensity, int durationTicks, int mergeWindowTicks,
        float surfaceWeight, float decay, boolean bloom, float volumetricStrength, int blockLightLevel) {
    /** 发动机表面光取现有预设范围上限；喷口自发光另外计算，不能靠放大照地半径冒充高亮。 */
    public static final float ENGINE_SURFACE_INTENSITY = 4F;
    /** 喷口核心的独立 HDR 峰值；8 盏光与全局 2 倍叠加后由 Shader 限制在浮点纹理范围内。 */
    public static final float ENGINE_CORE_INTENSITY = 60000F;
    /** 喷口周围橙色发光气团的 HDR 峰值，不依赖可选空气散射开关。 */
    public static final float ENGINE_HALO_INTENSITY = 16F;
    /** 一级喷口核心的物理半径，单位格。 */
    private static final float ENGINE_CORE_RADIUS = 0.28F;
    /** 橙色气团相对核心的半径倍数。 */
    private static final float ENGINE_HALO_RADIUS_SCALE = 3.5F;
    /** 爆炸半径只用作视觉输入，不允许核爆把屏幕光半径扩大到整张地图。 */
    public static RVP_LightParameters explosion(float explosionRadius) {
        float safeRadius = Float.isFinite(explosionRadius) ? Math.max(0, explosionRadius) : 4;
        return new RVP_LightParameters(true, 1, 0.82F, 0.48F,
                Math.min(32, 4 + safeRadius * 2), 1.25F, 8, 1, 0.85F, 2.2F, true, 0, 0);
    }

    /** 口径单位毫米；机枪弱短、重炮白亮，投放炸弹等无炮口火焰的武器由调用方排除。 */
    public static RVP_LightParameters muzzle(float caliber, boolean rocket) {
        float safeCaliber = Float.isFinite(caliber) && caliber > 0 ? caliber : 7.62F;
        boolean heavy = safeCaliber >= 45 && !rocket;
        return new RVP_LightParameters(true, 1, heavy ? 0.90F : 0.65F, heavy ? 0.72F : 0.25F,
                Math.min(18, (float) (2 + Math.sqrt(safeCaliber) * (rocket ? 0.7 : 1.1))),
                heavy ? 1.4F : rocket ? 0.8F : 0.45F, heavy ? 3 : 2,
                heavy ? 1 : 2, 0.8F, 1.6F, true, 0, 0);
    }

    /** 发动机只更新屏幕光；默认绝不逐 tick 写入真实方块光。 */
    public static RVP_LightParameters engine() {
        return new RVP_LightParameters(true, 1, 0.48F, 0.12F, 7, ENGINE_SURFACE_INTENSITY,
                3, 0, 0.8F, 1, true, 0, 0);
    }

    /** 按真实阶段/年龄生成独立喷口自发光；所有数值由代码控制，不加入武器 JSON。 */
    public static EngineEmission engineEmission(float radius, float intensity) {
        if (!Float.isFinite(radius) || radius <= 0 || !Float.isFinite(intensity) || intensity <= 0) {
            return new EngineEmission(0, 0, 0, 0);
        }
        float phase = Math.min(1F, intensity / ENGINE_SURFACE_INTENSITY);
        float coreRadius = Math.max(0.04F, ENGINE_CORE_RADIUS * Math.min(1F, radius / 7F));
        return new EngineEmission(coreRadius, coreRadius * ENGINE_HALO_RADIUS_SCALE,
                ENGINE_CORE_INTENSITY * phase, ENGINE_HALO_INTENSITY * phase);
    }

    /**
     * 只交给客户端统一灯光程序的喷口参数，不是新的粒子或真实方块光。
     * @param coreRadius 白热核心物理半径，单位格；0 表示无自发光
     * @param haloRadius 橙色气团物理半径，单位格；远处由屏幕最小覆盖补足
     * @param coreIntensity 核心 HDR 峰值，一级最大 60000（当前 HDR 输出安全上限），随阶段和燃尽衰减
     * @param haloIntensity 橙色气团 HDR 峰值，一级最大 16
     */
    public record EngineEmission(float coreRadius, float haloRadius, float coreIntensity, float haloIntensity) { }

    /** 一级最亮；二脉冲略收窄、偏暖并降低强度，临近燃尽再平滑收敛，但不延长真实燃烧窗口。 */
    public static RVP_LightParameters enginePhase(RVP_LightParameters parameters, boolean secondPulse, int remainingTicks) {
        float phase = (0.7F + 0.3F * Math.min(1, Math.max(0, remainingTicks) / 20.0F))
                * (secondPulse ? 0.7F : 1);
        return new RVP_LightParameters(parameters.enabled(), parameters.red(),
                parameters.green() * (secondPulse ? 0.9F : 1), parameters.blue() * (secondPulse ? 0.8F : 1),
                parameters.radius() * (secondPulse ? 0.8F : 1), parameters.intensity() * phase,
                parameters.durationTicks(), parameters.mergeWindowTicks(), parameters.surfaceWeight(), parameters.decay(),
                parameters.bloom(), parameters.volumetricStrength(), 0);
    }
}
