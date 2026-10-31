package org.ywzj.rvp.light;

/**
 * 由 Java 代码统一生成的不可变光源参数，不读取武器 JSON 光配置，不引用客户端类。
 * @param enabled 是否启用
 * @param red 红色，0～1
 * @param green 绿色，0～1
 * @param blue 蓝色，0～1
 * @param radius 未调参的照亮基础半径，单位格；光晕用同一基础值独立缩放，发动机基础为 20～500
 * @param intensity 代码预设亮度倍率为 0～4；游戏内调参允许额外放大
 * @param durationTicks 闪光寿命，单位 tick，1～40
 * @param mergeWindowTicks 同源短光合并窗口，单位 tick，0～10
 * @param surfaceWeight 代码预设表面受光倍率为 0～1；游戏内调参允许额外放大
 * @param decay 基础衰减指数，爆炸默认0.3；decayScale同时控制整盏短光的寿命和末段平滑淡出
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
    /** 发动机未调参基础半径下限，单位格，缺失口径时采用此值。 */
    public static final float ENGINE_MIN_RADIUS = 20F;
    /** 每毫米弹体口径增加的基础半径，单位格/mm；两种范围分别在此基础上调参。 */
    public static final float ENGINE_RADIUS_PER_MM = 0.1F;
    /** 发动机未调参基础半径上限，单位格；调参后的独立范围另有较宽上限。 */
    public static final float ENGINE_MAX_RADIUS = 500F;
    /** 一级喷口核心的物理半径，单位格。 */
    private static final float ENGINE_CORE_RADIUS = 0.28F;
    /** 橙色气团相对核心的半径倍数。 */
    private static final float ENGINE_HALO_RADIUS_SCALE = 3.5F;
    /** 爆炸半径只用作视觉输入，不允许核爆把屏幕光半径扩大到整张地图。 */
    public static RVP_LightParameters explosion(float explosionRadius) {
        float safeRadius = Float.isFinite(explosionRadius) ? Math.max(0, explosionRadius) : 4;
        // 正常20刻/秒时32刻为1.6秒；亮度按剩余寿命比例的0.3次幂衰减。
        return new RVP_LightParameters(true, 1, 0.82F, 0.48F,
                Math.min(32, 4 + safeRadius * 2), 1.25F, 32, 1, 0.85F, 0.3F, true, 0, 0);
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
        // 调用相同口径规则，命令及缺少明确口径的预览采用二十格基础半径。
        return engine(0);
    }

    /** 既有 effects_data.caliber 单位为毫米；提供照亮/光晕基础半径，二者分别调参，不改变喷焰尺寸。 */
    public static RVP_LightParameters engine(float caliber) {
        // 调用唯一口径基础半径入口，环境光和光晕各自的倍率由采样阶段独立应用。
        return new RVP_LightParameters(true, 1, 0.48F, 0.12F, engineRadius(caliber), ENGINE_SURFACE_INTENSITY,
                3, 0, 0.8F, 1, true, 0, 0);
    }

    /** 按毫米口径叠加，非法值回退最小值；有限超大值限制为五百格，不把 NaN/Inf 交给显卡。 */
    public static float engineRadius(float caliber) {
        float safeCaliber = Float.isFinite(caliber) ? Math.max(0F, caliber) : 0F;
        return Math.min(ENGINE_MAX_RADIUS, ENGINE_MIN_RADIUS + safeCaliber * ENGINE_RADIUS_PER_MM);
    }

    /** 光电干扰机玻片：小半径红光，照亮镜片周围一小圈，不做整车泛光。 */
    public static RVP_LightParameters eoJammer() {
        // 干扰机归入总设置分组，该分组不开放半径/权重倍率，因此下面每一项都是最终生效值。
        // 2.4 格照亮兼作光晕半径（该分组没有独立的光晕倍率），从"泛光"收成"一盏灯"。
        return new RVP_LightParameters(true, 1, 0.12F, 0.08F, 2.4F, 1.2F, 3, 0, 0.5F, 1.8F, true, 0, 0);
    }

    /**
     * 夜视仪下的光电干扰机：窗帘式干扰灯绝大部分能量在红外波段，肉眼只看到微弱红晕，
     * 像增强管却把它整片放大，所以同一盏灯在夜视下半径更大、亮度更高。
     * 光晕那一路仍按 {@code RVP_ScreenLightManager.EO_JAMMER_HALO_SCALE} 折算，
     * 3.0 × 0.4 = 1.2 相对肉眼那档 1.2 × 0.4 = 0.48 是 2.5 倍。
     */
    public static RVP_LightParameters eoJammerNightVision() {
        return new RVP_LightParameters(true, 1, 0.12F, 0.08F, 4.5F, 3.0F, 3, 0, 0.5F, 1.8F, true, 0, 0);
    }

    /**
     * 关掉这盏灯的光晕，保留照亮：渲染端按 {@code bloom} 只把 haloRadius/haloIntensity 归零，
     * 表面受光走 intensity 与 surfaceWeight，两条路互不影响。
     * 用于自己车的干扰灯——镜头就贴在玻片后面，光晕会糊住整个画面，但环境受光仍然要有。
     */
    public RVP_LightParameters withoutHalo() {
        return new RVP_LightParameters(enabled, red, green, blue, radius, intensity,
                durationTicks, mergeWindowTicks, surfaceWeight, decay, false, volumetricStrength, blockLightLevel);
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

    /** 一级最亮；二脉冲偏暖并减弱，临近燃尽再收敛；共同半径保持口径规则，不低于二十格。 */
    public static RVP_LightParameters enginePhase(RVP_LightParameters parameters, boolean secondPulse, int remainingTicks) {
        float phase = (0.7F + 0.3F * Math.min(1, Math.max(0, remainingTicks) / 20.0F))
                * (secondPulse ? 0.7F : 1);
        return new RVP_LightParameters(parameters.enabled(), parameters.red(),
                parameters.green() * (secondPulse ? 0.9F : 1), parameters.blue() * (secondPulse ? 0.8F : 1),
                Math.max(ENGINE_MIN_RADIUS, Math.min(ENGINE_MAX_RADIUS, parameters.radius())), parameters.intensity() * phase,
                parameters.durationTicks(), parameters.mergeWindowTicks(), parameters.surfaceWeight(), parameters.decay(),
                parameters.bloom(), parameters.volumetricStrength(), 0);
    }
}
