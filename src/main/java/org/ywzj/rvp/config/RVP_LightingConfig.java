package org.ywzj.rvp.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;

/** 客户端屏幕光画质设置；具体光源参数由代码控制，P5 真实方块光在本轮固定关闭。 */
public final class RVP_LightingConfig {
    /** 客户端规格；只读取配置值，不持有客户端类型。 */
    private static final ForgeConfigSpec CLIENT_SPEC;
    /** P5 在代码层暂停；不读取任何服务端配置文件，避免旧开关误启用真实方块光。 */
    private static final boolean P5_ENABLED = false;
    /** P5 保留代码的条目预算；当前仅为后续恢复开发保留，不开放配置。 */
    private static final int MAX_BLOCK_LIGHT_COUNT = 32;
    /** P5 保留代码的单次寿命预算，单位 tick；当前不允许创建新条目。 */
    private static final int MAX_BLOCK_LIGHT_LIFETIME = 10;
    /** 屏幕空间光总开关。 */
    private static final ForgeConfigSpec.BooleanValue SCREEN_LIGHT;
    /** 光晕总开关。 */
    private static final ForgeConfigSpec.BooleanValue BLOOM;
    /** 可选空气散射总开关，默认关闭。 */
    private static final ForgeConfigSpec.BooleanValue VOLUMETRIC;
    /** 每帧允许提交的光源数，最大八盏。 */
    private static final ForgeConfigSpec.IntValue MAX_LIGHTS;
    /** 最大观察距离，单位格。 */
    private static final ForgeConfigSpec.DoubleValue MAX_DISTANCE;
    /** 总强度倍率，0 也能完全关闭绘制。 */
    private static final ForgeConfigSpec.DoubleValue INTENSITY;

    static {
        ForgeConfigSpec.Builder client = new ForgeConfigSpec.Builder();
        SCREEN_LIGHT = client.comment("世界画面表面受光；支持 DH 与 Iris/Oculus 光影，不改变原版方块光照值。")
                .define("screenLight", true);
        BLOOM = client.comment("半分辨率亮部光晕；关闭不影响基础受光。") .define("bloom", true);
        VOLUMETRIC = client.comment("允许代码预设中的球形空气散射，默认关闭；不读取武器 JSON 光源配置。")
                .define("volumetric", false);
        MAX_LIGHTS = client.comment("每帧光源上限，0 关闭。默认 8。") .defineInRange("maxLights", 8, 0, 8);
        MAX_DISTANCE = client.comment("光源最大观察距离，单位格。") .defineInRange("maxDistance", 256.0, 16, 1024);
        INTENSITY = client.comment("屏幕光强度倍率，0 关闭。") .defineInRange("intensity", 1.0, 0, 2);
        CLIENT_SPEC = client.build();
    }

    private RVP_LightingConfig() { }

    /** 仅注册客户端画质；不再注册可开启 P5 的服务端光源配置文件。 */
    public static void register(ModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, "ywzj_rvp-lighting-client.toml");
    }

    /** 配置未加载时允许安全客户端默认值。 */
    public static boolean screenEnabled() {
        // 调用本机会话覆盖；未调参时沿用已加载的画质配置。
        return RVP_LightRuntimeTuning.getOrDefault(Group.global, Parameter.enabled,
                !CLIENT_SPEC.isLoaded() || SCREEN_LIGHT.get() ? 1 : 0) == 1;
    }
    /** 配置未加载时使用默认光晕。 */
    public static boolean bloomEnabled() {
        // 调用光晕临时开关；reset 后重新尊重 TOML，不改写磁盘文件。
        return RVP_LightRuntimeTuning.getOrDefault(Group.bloom, Parameter.enabled,
                !CLIENT_SPEC.isLoaded() || BLOOM.get() ? 1 : 0) == 1;
    }
    /** 默认关闭实验性空气散射。 */
    public static boolean volumetricEnabled() { return CLIENT_SPEC.isLoaded() && VOLUMETRIC.get(); }
    /** 返回客户端灯数预算。 */
    public static int maxLights() { return CLIENT_SPEC.isLoaded() ? MAX_LIGHTS.get() : 8; }
    /** 返回客户端距离预算。 */
    public static double maxDistance() {
        // 调用放宽后的距离倍率，允许1～32768格，不影响喷焰独立的远距可见性。
        return Math.max(1, Math.min(RVP_LightRuntimeTuning.MAX_TUNED_DISTANCE, (CLIENT_SPEC.isLoaded() ? MAX_DISTANCE.get() : 256)
                * RVP_LightRuntimeTuning.get(Group.global, Parameter.distanceScale)));
    }
    /** 返回客户端亮度倍率。 */
    public static float intensity() {
        // 调用总亮度倍率；真实光源与烟雾共用结果，喷焰保持独立自发光。
        return (CLIENT_SPEC.isLoaded() ? INTENSITY.get().floatValue() : 1)
                * RVP_LightRuntimeTuning.get(Group.global, Parameter.brightness);
    }
    /** 本轮固定返回 false；已有 TOML 的 blockLight=true 也不能开启真实方块光。 */
    public static boolean blockLightEnabled() { return P5_ENABLED; }
    /** 返回 P5 保留代码的条目预算；当前不会放置新光源。 */
    public static int maxBlockLights() { return MAX_BLOCK_LIGHT_COUNT; }
    /** 返回 P5 保留代码的单次寿命预算，单位 tick。 */
    public static int maxBlockLifetime() { return MAX_BLOCK_LIGHT_LIFETIME; }
}
