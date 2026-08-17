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

    /** Register the client config. Must be called from mod constructor. */
    public static void register(ModLoadingContext context) {
        Pair<RVP_ClientConfig, ForgeConfigSpec> specPair =
                new ForgeConfigSpec.Builder().configure(RVP_ClientConfig::new);
        INSTANCE = specPair.getLeft();
        context.registerConfig(ModConfig.Type.CLIENT, specPair.getRight(), "ywzj_rvp-client.toml");
    }
}
