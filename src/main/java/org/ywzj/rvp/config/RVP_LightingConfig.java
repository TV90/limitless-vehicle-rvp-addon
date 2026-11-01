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
    /** 爆炸照亮（屏幕空间表面受光）。 */
    private static final ForgeConfigSpec.BooleanValue EXPLOSION_LIGHT;
    /** 炮口照亮（屏幕空间表面受光）。 */
    private static final ForgeConfigSpec.BooleanValue MUZZLE_LIGHT;
    /** 发动机照亮（屏幕空间表面受光）；与广域橙晕分开控制。 */
    private static final ForgeConfigSpec.BooleanValue ENGINE_LIGHT;
    /** 爆炸白热核心（独立加色通道的自发光核心）。 */
    private static final ForgeConfigSpec.BooleanValue EXPLOSION_CORE;
    /** 爆炸广域光晕（世界空间光晕圆盘，需要 bloom 才能显示）。 */
    private static final ForgeConfigSpec.BooleanValue EXPLOSION_HALO;
    /** 天空散射（只染没有深度的天空像素）。 */
    private static final ForgeConfigSpec.BooleanValue SKY_SCATTER;
    /** 烟雾与炮口特殊贴图的受光层。 */
    private static final ForgeConfigSpec.BooleanValue SMOKE_LIGHT;
    /** 程序喷焰（独立通道，不受八灯预算与观察距离限制）。 */
    private static final ForgeConfigSpec.BooleanValue PLUME;
    /** 光晕总开关。 */
    private static final ForgeConfigSpec.BooleanValue BLOOM;
    /** 导弹发动机广域外圈橙晕；与发动机表面照亮分开。 */
    private static final ForgeConfigSpec.BooleanValue MISSILE_HALO;
    /** 光电干扰机玻片夜间红光总开关；关闭后玻片完全保持原贴图。 */
    private static final ForgeConfigSpec.BooleanValue EO_JAMMER_GLOW;
    /** 可选空气散射总开关，默认关闭。 */
    private static final ForgeConfigSpec.BooleanValue VOLUMETRIC;
    /** 每帧允许提交的光源数，最大八盏。 */
    private static final ForgeConfigSpec.IntValue MAX_LIGHTS;
    /** 最大观察距离，单位格。 */
    private static final ForgeConfigSpec.DoubleValue MAX_DISTANCE;
    /** 总强度倍率，0 也能完全关闭绘制。 */
    private static final ForgeConfigSpec.DoubleValue INTENSITY;
    /** 空中起爆离地高度门槛，单位格；0 表示不限制。 */
    private static final ForgeConfigSpec.DoubleValue AIR_BURST_CLEARANCE;
    /** 发光载具的结构盒是否遮挡自己的持续光；默认关闭，避免玻片灯被车壳埋住。 */
    private static final ForgeConfigSpec.BooleanValue SOURCE_VEHICLE_OCCLUDES_OWN_LIGHT;

    static {
        ForgeConfigSpec.Builder client = new ForgeConfigSpec.Builder();
        SCREEN_LIGHT = client.comment("屏幕空间光总开关：关闭后照亮、光晕、白热核心、天空散射与烟雾受光全部不绘制。",
                        "光晕、核心、天空等分项见下方同名开关；粒子、喷焰与声音不受影响。")
                .define("screenLight", true);
        EXPLOSION_LIGHT = client.comment("爆炸照亮：关闭后爆炸不再照亮地面/墙面/实体，粒子、烟雾与声音照常。")
                .define("explosionLight", true);
        MUZZLE_LIGHT = client.comment("炮口照亮：关闭后开火不再照亮周围环境。")
                .define("muzzleLight", true);
        ENGINE_LIGHT = client.comment("发动机照亮：关闭后飞行中的导弹不再照亮周围墙面与烟雾；核心喷焰不受影响。")
                .define("engineLight", true);
        EXPLOSION_CORE = client.comment("爆炸白热核心：炸点的圆形高亮核心；关闭后只保留照亮与光晕。")
                .define("explosionCore", true);
        EXPLOSION_HALO = client.comment("爆炸广域光晕：炸点周围的世界空间光晕，需要 bloom 才能显示。")
                .define("explosionHalo", true);
        SKY_SCATTER = client.comment("天空散射：爆炸把整片夜空染亮；关闭后天空像素完全不受爆炸光影响。")
                .define("skyScatter", true);
        SMOKE_LIGHT = client.comment("烟雾与炮口特殊贴图受光：关闭后烟雾贴图不再接收环境光。")
                .define("smokeLight", true);
        PLUME = client.comment("程序喷焰：导弹尾部的定向喷流，独立于八灯预算与观察距离；关闭后回到旧尾焰粒子。")
                .define("plume", true);
        BLOOM = client.comment("半分辨率亮部光晕；关闭不影响基础受光。") .define("bloom", true);
        MISSILE_HALO = client.comment("导弹发动机的广域外圈橙晕；关闭只去掉外圈，发动机表面照亮由 engineLight 控制。")
                .define("missileHalo", true);
        EO_JAMMER_GLOW = client.comment("光电干扰机玻片在夜间/暗处乘红色并发光；关闭后玻片完全保持原贴图。",
                        "玻片骨骼与对应干扰模块由 display JSON 的 eo_jammer_glow 指定。")
                .define("eoJammerGlow", true);
        VOLUMETRIC = client.comment("允许代码预设中的球形空气散射，默认关闭；不读取武器 JSON 光源配置。")
                .define("volumetric", false);
        MAX_LIGHTS = client.comment("每帧光源上限，0 关闭。默认 8。") .defineInRange("maxLights", 8, 0, 8);
        MAX_DISTANCE = client.comment("光源最大观察距离，单位格。默认 5012；旧配置文件需手动改成同一数值。") .defineInRange("maxDistance", 5012.0, 16, 32768);
        INTENSITY = client.comment("屏幕光强度倍率，0 关闭。") .defineInRange("intensity", 1.0, 0, 2);
        AIR_BURST_CLEARANCE = client.comment("空中起爆离地高度门槛，单位格；默认 100，0 表示不限制（贴地爆炸也生成爆炸光源）。",
                        "服务端只下发爆心离地高度，是否建灯由本客户端门槛决定；只影响爆炸屏幕光源，粒子与声音不受影响。")
                .defineInRange("airBurstClearance", 100.0, 0, 32768);
        SOURCE_VEHICLE_OCCLUDES_OWN_LIGHT = client.comment("发光载具自己的结构盒是否遮挡它的持续光。",
                        "默认关闭：玻片灯可能位于车体盒内；其它载具与方块仍正常参与遮挡。")
                .define("sourceVehicleOccludesOwnLight", false);
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
                configuredScreenEnabled() ? 1 : 0) == 1;
    }
    /** 配置未加载时使用默认光晕。 */
    public static boolean bloomEnabled() {
        // 调用光晕临时开关；reset 后重新尊重 TOML，不改写磁盘文件。
        return RVP_LightRuntimeTuning.getOrDefault(Group.bloom, Parameter.enabled,
                configuredBloomEnabled() ? 1 : 0) == 1;
    }
    /** 返回原始画质选择，供调参面板显示真正的默认值；不读取会话覆盖。 */
    public static boolean configuredScreenEnabled() { return !CLIENT_SPEC.isLoaded() || SCREEN_LIGHT.get(); }
    /** 返回原始光晕选择；恢复默认后继续尊重玩家已有 TOML。 */
    public static boolean configuredBloomEnabled() { return !CLIENT_SPEC.isLoaded() || BLOOM.get(); }
    /** 返回原始发动机广域橙晕选择；默认开启，面板不会自行关闭。 */
    public static boolean configuredMissileHaloEnabled() { return !CLIENT_SPEC.isLoaded() || MISSILE_HALO.get(); }
    /** 导弹发动机广域橙晕；只控制外圈，发动机表面照亮见 {@link #engineLightEnabled()}。 */
    public static boolean missileHaloEnabled() {
        // 调用本地会话开关；未显式调参时沿用客户端配置。
        return RVP_LightRuntimeTuning.getOrDefault(Group.engine, Parameter.enabled,
                configuredMissileHaloEnabled() ? 1 : 0) == 1;
    }

    /** 爆炸照亮；关闭只去掉爆炸对环境的表面受光，粒子与声音照常。 */
    public static boolean explosionLightEnabled() { return enabledByDefault(EXPLOSION_LIGHT); }
    /** 炮口照亮；关闭只去掉开火对环境的表面受光。 */
    public static boolean muzzleLightEnabled() { return enabledByDefault(MUZZLE_LIGHT); }
    /** 发动机照亮；与广域橙晕独立，关闭后仍可保留橙晕与核心喷焰。 */
    public static boolean engineLightEnabled() { return enabledByDefault(ENGINE_LIGHT); }
    /** 爆炸白热核心；关闭后只保留照亮与光晕。 */
    public static boolean explosionCoreEnabled() { return enabledByDefault(EXPLOSION_CORE); }
    /** 爆炸广域光晕；仍需要 bloom 才能实际显示。 */
    public static boolean explosionHaloEnabled() { return enabledByDefault(EXPLOSION_HALO); }
    /** 天空散射；关闭后天空像素完全不受爆炸光影响。 */
    public static boolean skyScatterEnabled() { return enabledByDefault(SKY_SCATTER); }
    /** 烟雾与炮口特殊贴图受光。 */
    public static boolean smokeLightEnabled() { return enabledByDefault(SMOKE_LIGHT); }
    /** 程序喷焰；关闭后回到旧尾焰粒子，和八灯预算、观察距离无关。 */
    public static boolean plumeEnabled() { return enabledByDefault(PLUME); }

    /**
     * 空中起爆离地高度门槛，单位格；0 表示不限制（贴地爆炸也生成光源）。
     *
     * <p>读取顺序：本机 {@code /rvplight set global airBurstClearance} 覆盖 → 客户端 TOML →
     * 代码默认 100 格。服务端只下发离地高度事实，判定始终使用玩家自己的设置。</p>
     */
    public static float airBurstClearance() {
        // 调用全局会话覆盖；未调参时沿用已加载的客户端配置。
        return RVP_LightRuntimeTuning.getOrDefault(Group.global, Parameter.airBurstClearance,
                configuredAirBurstClearance());
    }

    /** 返回原始 TOML 门槛值，供调参面板显示真正的默认值；不读取会话覆盖。 */
    public static float configuredAirBurstClearance() {
        return CLIENT_SPEC.isLoaded() ? AIR_BURST_CLEARANCE.get().floatValue() : 100F;
    }

    /** 单项开关的统一读取：配置未加载时按代码默认（开启）处理，避免启动首帧短暂缺光。 */
    private static boolean enabledByDefault(ForgeConfigSpec.BooleanValue value) {
        return !CLIENT_SPEC.isLoaded() || value.get();
    }

    /** 光电干扰机玻片夜间红光；配置未加载时按开启处理，与 TOML 默认值一致。 */
    public static boolean eoJammerGlowEnabled() { return enabledByDefault(EO_JAMMER_GLOW); }
    /** 默认关闭实验性空气散射。 */
    public static boolean volumetricEnabled() { return CLIENT_SPEC.isLoaded() && VOLUMETRIC.get(); }
    /** 返回客户端灯数预算。 */
    public static int maxLights() { return CLIENT_SPEC.isLoaded() ? MAX_LIGHTS.get() : 8; }
    /** 返回客户端距离预算。 */
    public static double maxDistance() {
        // 调用放宽后的距离倍率，允许1～32768格，不影响喷焰独立的远距可见性。
        return Math.max(1, Math.min(RVP_LightRuntimeTuning.MAX_TUNED_DISTANCE, (CLIENT_SPEC.isLoaded() ? MAX_DISTANCE.get() : 5012.0)
                * RVP_LightRuntimeTuning.get(Group.global, Parameter.distanceScale)));
    }
    /** 返回客户端亮度倍率。 */
    public static float intensity() {
        // 调用总亮度倍率；真实光源与烟雾共用结果，喷焰保持独立自发光。
        return (CLIENT_SPEC.isLoaded() ? INTENSITY.get().floatValue() : 1)
                * RVP_LightRuntimeTuning.get(Group.global, Parameter.brightness);
    }
    /** 保留远端新增的本车遮挡选择；配置未加载时按默认不遮挡处理。 */
    public static boolean sourceVehicleOccludesOwnLight() {
        return CLIENT_SPEC.isLoaded() && SOURCE_VEHICLE_OCCLUDES_OWN_LIGHT.get();
    }
    /** 本轮固定返回 false；已有 TOML 的 blockLight=true 也不能开启真实方块光。 */
    public static boolean blockLightEnabled() { return P5_ENABLED; }
    /** 返回 P5 保留代码的条目预算；当前不会放置新光源。 */
    public static int maxBlockLights() { return MAX_BLOCK_LIGHT_COUNT; }
    /** 返回 P5 保留代码的单次寿命预算，单位 tick。 */
    public static int maxBlockLifetime() { return MAX_BLOCK_LIGHT_LIFETIME; }
}


