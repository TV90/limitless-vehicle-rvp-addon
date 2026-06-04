package org.ywzj.rvp.weapon.data;

import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * How candidate cells are chosen when {@link RVP_DispenserPayloadData#getDensity()} is below 100.
 */
public enum RVP_EnumDispenserDistribution implements StringRepresentable {
    /** Each selected cell is equally likely. */
    UNIFORM("uniform"),
    /** Gaussian falloff from center (higher density near impact). */
    NORMAL("normal"),
    /** Alias: strong bias to center (low sigma). */
    CLUSTER_CENTER("cluster_center"),
    /** Bias toward the outer shell of the shape. */
    CLUSTER_EDGE("cluster_edge"),
    /** Prefer a ring at ~70% of horizontal radius (circle/cylinder/square). */
    RING("ring");

    private final String id;

    RVP_EnumDispenserDistribution(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public static RVP_EnumDispenserDistribution fromString(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return UNIFORM;
        }
        String key = raw.trim().toLowerCase();
        for (RVP_EnumDispenserDistribution dist : values()) {
            if (dist.id.equals(key)) {
                return dist;
            }
        }
        return UNIFORM;
    }

    /**
     * Selection weight for a cell at offset (x,y,z). Higher = more likely when subsampling.
     */
    public float weight(int x, int y, int z, int radius, RVP_EnumDispenserSpreadShape shape, RandomSource random) {
        double dist = horizontalDistance(x, z, shape, radius);
        double norm = radius <= 0 ? 0.0 : dist / radius;
        return switch (this) {
            case UNIFORM -> 1.0f;
            case NORMAL -> (float) Math.exp(-norm * norm * 2.0);
            case CLUSTER_CENTER -> (float) Math.exp(-norm * norm * 5.0);
            case CLUSTER_EDGE -> norm < 0.55f ? 0.15f : (float) Math.pow(norm, 1.5);
            case RING -> {
                float ring = 0.7f;
                float band = 0.18f;
                float d = Math.abs((float) norm - ring);
                yield d < band ? 1.0f : 0.08f;
            }
        };
    }

    private static double horizontalDistance(int x, int z, RVP_EnumDispenserSpreadShape shape, int radius) {
        return switch (shape) {
            case SQUARE, CUBE, DIAMOND -> Math.max(Math.abs(x), Math.abs(z));
            default -> Math.sqrt(x * x + z * z);
        };
    }
}
