package org.ywzj.rvp.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

public class RVP_CommonConfig {

    private static RVP_CommonConfig INSTANCE;

    private final ForgeConfigSpec.BooleanValue spawnVehicleWithCreativeAmmo;

    public RVP_CommonConfig(ForgeConfigSpec.Builder builder) {
        builder.push("spawning");

        spawnVehicleWithCreativeAmmo = builder
                .comment(
                        "When placing a vehicle with the spawn item, automatically add a stack of creative ammo",
                        "to the vehicle's inventory so it can be used immediately.",
                        "Default: false"
                )
                .define("spawnVehicleWithCreativeAmmo", false);

        builder.pop();
    }

    public static boolean isSpawnVehicleWithCreativeAmmo() {
        return INSTANCE != null && INSTANCE.spawnVehicleWithCreativeAmmo.get();
    }

    /** Register the common config. Must be called from mod constructor. */
    public static void register(ModLoadingContext context) {
        Pair<RVP_CommonConfig, ForgeConfigSpec> specPair =
                new ForgeConfigSpec.Builder().configure(RVP_CommonConfig::new);
        INSTANCE = specPair.getLeft();
        context.registerConfig(ModConfig.Type.COMMON, specPair.getRight(), "ywzj_rvp-common.toml");
    }
}
