package org.ywzj.rvp.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

/**
 * RVP 客户端配置（{@code ywzj_rvp-client.toml}）。
 * <p>
 * 目前包含 LOD 缩放过滤：缩放中（载具镜/RVP 武器缩放/原版望远镜）视场内载具少、
 * 渲染压力低，可将 LOD 距离阈值放大、更晚才替换低模。
 * 以及温压粒子质量、音量、闪光和震动的客户端上限。
 */
public class RVP_ClientConfig {

    /** Distant Horizons 兼容通道选择。 */
    public enum DistantHorizonsCompatMode {
        /** 完全关闭 DH 适配，继续使用原有实体后渲染通道。 */
        OFF,
        /** 在世界末端把完整远距载具绘制到 DH 地形之上，保证不被 DH 覆盖。 */
        RVP_FIRST,
        /** 优先深度感知合成，失败时使用配置的安全降级。 */
        AUTO,
        /** 只接受深度感知合成；失败时隐藏本帧远距载具。 */
        DEPTH_AWARE
    }

    /** Distant Horizons 深度感知不可用时的渲染语义。 */
    public enum DistantHorizonsFallbackMode {
        /** 继续原有 AFTER_ENTITIES 通道，可能仍被后续 DH 通道覆盖。 */
        CURRENT_PASS,
        /** 在世界末端绘制仅含边缘的青色目标轮廓。 */
        SILHOUETTE,
        /** 在世界末端合成完整载具并忽略地形深度，允许穿山。 */
        ALWAYS_VISIBLE,
        /** 隐藏本帧无法可信合成的远距载具。 */
        HIDE
    }

    /** 客户端温压粒子质量档位。 */
    public enum ThermobaricQuality {
        /** 低质量：保留作者粒子密度的 50%。 */
        LOW(0.50F),
        /** 中质量：保留作者粒子密度的 75%。 */
        MEDIUM(0.75F),
        /** 高质量：完整保留作者粒子密度。 */
        HIGH(1.00F);

        /** 当前质量档位对应的粒子密度上限倍率。 */
        private final float densityMultiplier;

        ThermobaricQuality(float densityMultiplier) {
            this.densityMultiplier = densityMultiplier;
        }

        /** 返回只会下调作者粒子密度的倍率。 */
        public float densityMultiplier() {
            return densityMultiplier;
        }
    }

    /** 已注册的客户端配置实例。 */
    private static RVP_ClientConfig INSTANCE;

    /** 缩放观察时是否延后载具 LOD 切换。 */
    private final ForgeConfigSpec.BooleanValue lodZoomEnabled;
    /** 判定进入缩放观察状态的视场角比例。 */
    private final ForgeConfigSpec.DoubleValue lodZoomFovRatio;
    /** 缩放观察时使用的载具 LOD 距离倍率。 */
    private final ForgeConfigSpec.DoubleValue lodZoomDistanceMultiplier;
    /** 温压效果的客户端粒子质量档位。 */
    private final ForgeConfigSpec.EnumValue<ThermobaricQuality> thermobaricQuality;
    /** 温压音效的客户端音量倍率。 */
    private final ForgeConfigSpec.DoubleValue thermobaricSoundVolume;
    /** 温压闪光的客户端强度倍率。 */
    private final ForgeConfigSpec.DoubleValue thermobaricFlashIntensity;
    /** 温压镜头震动的客户端强度倍率。 */
    private final ForgeConfigSpec.DoubleValue thermobaricShakeIntensity;
    /** 客户端每帧允许绘制的最大远距载具数。 */
    private final ForgeConfigSpec.IntValue remoteVehicleMaxRenderedVehicles;
    /** 客户端每帧允许绘制的最大无 LOD 高模载具数。 */
    private final ForgeConfigSpec.IntValue remoteVehicleMaxFallbackHighModels;
    /** 客户端远距载具样本允许的最大外推 tick 数。 */
    private final ForgeConfigSpec.IntValue remoteVehicleMaxExtrapolationTicks;
    /** Distant Horizons 远距载具兼容模式。 */
    private final ForgeConfigSpec.EnumValue<DistantHorizonsCompatMode> distantHorizonsCompatMode;
    /** Distant Horizons 深度合成失败后的降级模式。 */
    private final ForgeConfigSpec.EnumValue<DistantHorizonsFallbackMode> distantHorizonsFallbackMode;
    /** DH 简化地形遮挡的基础视深度容差，单位格。 */
    private final ForgeConfigSpec.DoubleValue distantHorizonsOcclusionBiasBlocks;
    /** DH 简化地形遮挡容差的硬上限，单位格。 */
    private final ForgeConfigSpec.DoubleValue distantHorizonsMaxOcclusionBiasBlocks;
    /** 是否把客户端已加载的真实载具加入 DH 颜色与深度保护层。 */
    private final ForgeConfigSpec.BooleanValue distantHorizonsProtectTrackedVehicles;
    /** 真实载具保护层相对 DH 简化地形的视深度容差，单位格。 */
    private final ForgeConfigSpec.DoubleValue distantHorizonsTrackedOcclusionBiasBlocks;
    /** 每帧允许进入 DH 保护层的真实载具上限。 */
    private final ForgeConfigSpec.IntValue distantHorizonsMaxProtectedTrackedVehicles;
    /** 是否允许在未经实机验证的光影/延迟透明管线中实验性合成。 */
    private final ForgeConfigSpec.BooleanValue distantHorizonsAllowExperimentalShaderPipeline;
    /** 是否输出受频率限制的 DH 兼容诊断。 */
    private final ForgeConfigSpec.BooleanValue distantHorizonsDiagnostics;
    /** 是否启用分层诊断染色；默认 false，仅在 diagnostics=true 时生效，不改变深度。 */
    private final ForgeConfigSpec.BooleanValue distantHorizonsDiagnosticLayerColors;

    public RVP_ClientConfig(ForgeConfigSpec.Builder builder) {
        builder.push("lod");

        lodZoomEnabled = builder
                .comment(
                        "Enable the LOD zoom filter: while the player is zoomed (vehicle scope,",
                        "RVP weapon zoom, or vanilla spyglass), LOD distance thresholds are",
                        "multiplied by lodZoomDistanceMultiplier to keep more detail.",
                        "Default: true"
                )
                .define("lodZoomEnabled", true);

        lodZoomFovRatio = builder
                .comment(
                        "FOV ratio for the 'zoomed' state: when the current rendered FOV drops below",
                        "this fraction of the configured base FOV, the player is considered zoomed.",
                        "Default: 0.75"
                )
                .defineInRange("lodZoomFovRatio", 0.75, 0.1, 0.99);

        lodZoomDistanceMultiplier = builder
                .comment(
                        "LOD distance threshold multiplier applied while zoomed.",
                        "1.0 = unchanged; >1 = LOD activates farther away while zoomed.",
                        "Default: 1.5"
                )
                .defineInRange("lodZoomDistanceMultiplier", 1.5, 1.0, 10.0);

        builder.pop();

        builder.push("thermobaric");

        thermobaricQuality = builder
                .comment(
                        "Thermobaric particle quality. LOW/MEDIUM/HIGH keep 50%/75%/100%",
                        "of the particle density allowed by the weapon author.",
                        "Default: HIGH"
                )
                .defineEnum("thermobaricQuality", ThermobaricQuality.HIGH);

        thermobaricSoundVolume = builder
                .comment(
                        "Thermobaric sound volume multiplier. 0 disables thermobaric audio.",
                        "Default: 1.0"
                )
                .defineInRange("thermobaricSoundVolume", 1.0D, 0.0D, 1.0D);

        thermobaricFlashIntensity = builder
                .comment(
                        "Thermobaric screen flash intensity multiplier. 0 disables the flash.",
                        "Default: 1.0"
                )
                .defineInRange("thermobaricFlashIntensity", 1.0D, 0.0D, 1.0D);

        thermobaricShakeIntensity = builder
                .comment(
                        "Thermobaric camera shake intensity multiplier. 0 disables camera shake.",
                        "Default: 1.0"
                )
                .defineInRange("thermobaricShakeIntensity", 1.0D, 0.0D, 1.0D);

        builder.pop();

        builder.push("remoteVehicleRendering");

        remoteVehicleMaxRenderedVehicles = builder
                .comment("客户端每帧允许绘制的最大远距载具数。范围：0..1024，默认：32",
                        "该值只能缩小服务端授权集合，不能扩大服务端可见范围。")
                .defineInRange("maxRenderedVehicles", 32, 0, 1024);

        remoteVehicleMaxFallbackHighModels = builder
                .comment("客户端每帧允许绘制的最大无 LOD 高模载具数。范围：0..1024，默认：8",
                        "实际返回值不会超过 maxRenderedVehicles。")
                .defineInRange("maxFallbackHighModels", 8, 0, 1024);

        remoteVehicleMaxExtrapolationTicks = builder
                .comment("客户端远距载具样本允许的最大外推时间，单位 tick。范围：0..5，默认：5",
                        "该值只能缩短协议规定的五 tick 上限。")
                .defineInRange("maxExtrapolationTicks", 5, 0, 5);

        builder.push("distantHorizons");

        distantHorizonsCompatMode = builder
                .comment("Distant Horizons 远距载具兼容模式：OFF/RVP_FIRST/AUTO/DEPTH_AWARE。",
                        "RVP_FIRST 保证完整载具显示在 DH 地形之上；AUTO 默认使用深度感知合成，失败时进入 fallbackMode。")
                .defineEnum("compatMode", DistantHorizonsCompatMode.AUTO);

        distantHorizonsFallbackMode = builder
                .comment("深度感知不可用时：CURRENT_PASS/SILHOUETTE/ALWAYS_VISIBLE/HIDE。",
                        "ALWAYS_VISIBLE 会穿山，只有明确接受该语义时才应启用。")
                .defineEnum("fallbackMode", DistantHorizonsFallbackMode.SILHOUETTE);

        distantHorizonsOcclusionBiasBlocks = builder
                .comment("DH 简化 LOD 轮廓的基础遮挡容差，单位格。范围：0..8，默认：2。")
                .defineInRange("occlusionBiasBlocks", 2.0D, 0.0D, 8.0D);

        distantHorizonsMaxOcclusionBiasBlocks = builder
                .comment("DH 遮挡容差硬上限，单位格。范围：0..8，默认：8。")
                .defineInRange("maxOcclusionBiasBlocks", 8.0D, 0.0D, 8.0D);

        distantHorizonsProtectTrackedVehicles = builder
                .comment("是否把客户端已加载的真实载具加入 DH 颜色与深度保护层。",
                        "只影响客户端画面，不改变服务端实体追踪或远距快照。默认：true。")
                .define("protectTrackedVehicles", true);

        distantHorizonsTrackedOcclusionBiasBlocks = builder
                .comment("真实载具相对 DH 简化地形的遮挡容差，单位格。范围：0..2，默认：0.5。",
                        "该值独立于远距载具的 occlusionBiasBlocks，避免近处明显穿墙。")
                .defineInRange("trackedOcclusionBiasBlocks", 0.5D, 0.0D, 2.0D);

        distantHorizonsMaxProtectedTrackedVehicles = builder
                .comment("每帧进入 DH 保护层的真实载具安全上限。范围：1..256，默认：64。",
                        "超过上限时优先保留屏幕贡献更大、距离更近的载具。")
                .defineInRange("maxProtectedTrackedVehicles", 64, 1, 256);

        distantHorizonsAllowExperimentalShaderPipeline = builder
                .comment("是否允许未经验证的光影/延迟透明 DH 管线使用实验性纹理合成。",
                        "默认 false；关闭时明确进入安全降级。")
                .define("allowExperimentalShaderPipeline", false);

        distantHorizonsDiagnostics = builder
                .comment("是否每秒至多一次输出 DH 兼容状态与耗时统计。")
                .define("diagnostics", false);

        distantHorizonsDiagnosticLayerColors = builder
                .comment("是否将 DH 合成的远距代理染为青色、真实载具保护层染为品红色。",
                        "默认 false，仅在 diagnostics=true 时生效；保留真实深度与遮挡判断。")
                .define("diagnosticLayerColors", false);

        builder.pop();

        builder.pop();
    }

    public static boolean isLodZoomEnabled() {
        return INSTANCE != null && INSTANCE.lodZoomEnabled.get();
    }

    public static double getLodZoomFovRatio() {
        return INSTANCE != null ? INSTANCE.lodZoomFovRatio.get() : 0.75;
    }

    public static double getLodZoomDistanceMultiplier() {
        return INSTANCE != null ? INSTANCE.lodZoomDistanceMultiplier.get() : 1.5;
    }

    /** 返回温压粒子质量倍率；配置尚未注册时使用高质量默认值。 */
    public static float getThermobaricQualityDensity() {
        return INSTANCE != null
                ? INSTANCE.thermobaricQuality.get().densityMultiplier()
                : ThermobaricQuality.HIGH.densityMultiplier();
    }

    /** 返回温压音效音量倍率，范围为 {@code 0..1}。 */
    public static float getThermobaricSoundVolume() {
        return INSTANCE != null ? INSTANCE.thermobaricSoundVolume.get().floatValue() : 1.0F;
    }

    /** 返回温压闪光强度倍率，范围为 {@code 0..1}。 */
    public static float getThermobaricFlashIntensity() {
        return INSTANCE != null ? INSTANCE.thermobaricFlashIntensity.get().floatValue() : 1.0F;
    }

    /** 返回温压镜头震动强度倍率，范围为 {@code 0..1}。 */
    public static float getThermobaricShakeIntensity() {
        return INSTANCE != null ? INSTANCE.thermobaricShakeIntensity.get().floatValue() : 1.0F;
    }

    /** 返回客户端每帧允许绘制的最大远距载具数。 */
    public static int getRemoteVehicleMaxRenderedVehicles() {
        return INSTANCE != null ? INSTANCE.remoteVehicleMaxRenderedVehicles.get() : 32;
    }

    /** 返回客户端每帧允许绘制的最大无 LOD 高模载具数，且不超过总渲染上限。 */
    public static int getRemoteVehicleMaxFallbackHighModels() {
        if (INSTANCE == null) {
            return 8;
        }
        return Math.min(INSTANCE.remoteVehicleMaxFallbackHighModels.get(),
                INSTANCE.remoteVehicleMaxRenderedVehicles.get());
    }

    /** 返回客户端远距载具样本允许的最大外推 tick 数。 */
    public static int getRemoteVehicleMaxExtrapolationTicks() {
        return INSTANCE != null ? Math.min(INSTANCE.remoteVehicleMaxExtrapolationTicks.get(), 5) : 5;
    }

    /** 返回 Distant Horizons 兼容模式；配置未就绪时使用 AUTO。 */
    public static DistantHorizonsCompatMode getDistantHorizonsCompatMode() {
        return INSTANCE != null ? INSTANCE.distantHorizonsCompatMode.get() : DistantHorizonsCompatMode.AUTO;
    }

    /** 返回 DH 合成失败后的降级模式；DEPTH_AWARE 固定按 HIDE 处理。 */
    public static DistantHorizonsFallbackMode getDistantHorizonsFallbackMode() {
        if (getDistantHorizonsCompatMode() == DistantHorizonsCompatMode.DEPTH_AWARE) {
            return DistantHorizonsFallbackMode.HIDE;
        }
        return INSTANCE != null
                ? INSTANCE.distantHorizonsFallbackMode.get()
                : DistantHorizonsFallbackMode.SILHOUETTE;
    }

    /** 返回受硬上限约束的 DH 遮挡容差，单位格。 */
    public static float getDistantHorizonsOcclusionBiasBlocks() {
        if (INSTANCE == null) {
            return 2.0F;
        }
        return (float) Math.min(INSTANCE.distantHorizonsOcclusionBiasBlocks.get(),
                INSTANCE.distantHorizonsMaxOcclusionBiasBlocks.get());
    }

    /** 返回是否保护客户端已经加载的真实载具。 */
    public static boolean shouldProtectDistantHorizonsTrackedVehicles() {
        return INSTANCE == null || INSTANCE.distantHorizonsProtectTrackedVehicles.get();
    }

    /** 返回真实载具保护层的 DH 遮挡容差，单位格，范围固定为 {@code 0..2}。 */
    public static float getDistantHorizonsTrackedOcclusionBiasBlocks() {
        return INSTANCE != null
                ? INSTANCE.distantHorizonsTrackedOcclusionBiasBlocks.get().floatValue()
                : 0.5F;
    }

    /** 返回每帧允许进入 DH 保护层的真实载具上限。 */
    public static int getDistantHorizonsMaxProtectedTrackedVehicles() {
        return INSTANCE != null ? INSTANCE.distantHorizonsMaxProtectedTrackedVehicles.get() : 64;
    }

    /** 返回是否允许实验性光影/延迟透明合成。 */
    public static boolean isDistantHorizonsExperimentalShaderPipelineAllowed() {
        return INSTANCE != null && INSTANCE.distantHorizonsAllowExperimentalShaderPipeline.get();
    }

    /** 返回是否启用受频率限制的 DH 兼容诊断。 */
    public static boolean isDistantHorizonsDiagnosticsEnabled() {
        return INSTANCE != null && INSTANCE.distantHorizonsDiagnostics.get();
    }

    /** 返回是否启用仅改变颜色的 DH 分层探针；关闭诊断总开关时同时禁用染色。 */
    public static boolean isDistantHorizonsDiagnosticLayerColorsEnabled() {
        // 调用本项目诊断总开关，保证关闭诊断后不会残留可视探针。
        return isDistantHorizonsDiagnosticsEnabled() && INSTANCE.distantHorizonsDiagnosticLayerColors.get();
    }

    /** Register the client config. Must be called from mod constructor. */
    public static void register(ModLoadingContext context) {
        Pair<RVP_ClientConfig, ForgeConfigSpec> specPair =
                new ForgeConfigSpec.Builder().configure(RVP_ClientConfig::new);
        INSTANCE = specPair.getLeft();
        context.registerConfig(ModConfig.Type.CLIENT, specPair.getRight(), "ywzj_rvp-client.toml");
    }
}
