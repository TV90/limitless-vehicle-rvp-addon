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
 */
public class RVP_ClientConfig {

    private static RVP_ClientConfig INSTANCE;

    private final ForgeConfigSpec.BooleanValue lodZoomEnabled;
    private final ForgeConfigSpec.DoubleValue lodZoomFovRatio;
    private final ForgeConfigSpec.DoubleValue lodZoomDistanceMultiplier;

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

    /** Register the client config. Must be called from mod constructor. */
    public static void register(ModLoadingContext context) {
        Pair<RVP_ClientConfig, ForgeConfigSpec> specPair =
                new ForgeConfigSpec.Builder().configure(RVP_ClientConfig::new);
        INSTANCE = specPair.getLeft();
        context.registerConfig(ModConfig.Type.CLIENT, specPair.getRight(), "ywzj_rvp-client.toml");
    }
}
