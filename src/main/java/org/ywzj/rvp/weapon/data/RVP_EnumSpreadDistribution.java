package org.ywzj.rvp.weapon.data;

import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil;

/**
 * Spread density within a {@link RVP_EnumSpreadShape} footprint.
 * Used by {@link RVP_DispenserPayloadData} placement and {@link RVP_FireData} canister pellets.
 */
public enum RVP_EnumSpreadDistribution implements StringRepresentable {
    /** Each candidate / axis equally likely (circular footprint uses uniform disk). */
    UNIFORM("uniform"),
    /** Gaussian falloff from center (higher density near impact / bore). */
    NORMAL("normal"),
    /** Strong bias to center (low sigma). */
    CLUSTER_CENTER("cluster_center"),
    /** Bias toward the outer shell of the shape. */
    CLUSTER_EDGE("cluster_edge"),
    /** Prefer a ring at ~70% of horizontal radius (circle/cylinder/square). */
    RING("ring");

    private final String id;

    RVP_EnumSpreadDistribution(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public static RVP_EnumSpreadDistribution fromString(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return UNIFORM;
        }
        String key = raw.trim().toLowerCase();
        for (RVP_EnumSpreadDistribution dist : values()) {
            if (dist.id.equals(key)) {
                return dist;
            }
        }
        return UNIFORM;
    }

    public float weight(int x, int y, int z, int radius, RVP_EnumSpreadShape shape, RandomSource random) {
        return RVP_SpreadDistributionUtil.weight(this, x, y, z, radius, shape, random);
    }
}
