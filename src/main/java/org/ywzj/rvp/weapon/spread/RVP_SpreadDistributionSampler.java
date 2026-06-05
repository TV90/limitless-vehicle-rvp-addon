package org.ywzj.rvp.weapon.spread;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadDistribution;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadShape;

/**
 * Shared spread sampling for dispenser placement and {@link org.ywzj.rvp.weapon.data.RVP_FireData}
 * canister pellets (position / angle offset).
 */
public final class RVP_SpreadDistributionSampler {

    private static final int MAX_REJECTION_ATTEMPTS = 64;

    private RVP_SpreadDistributionSampler() {}

    /**
     * Selection weight for a cell at offset (x,y,z). Higher = more likely when subsampling.
     */
    public static float weight(RVP_EnumSpreadDistribution distribution, int x, int y, int z, int radius,
                               RVP_EnumSpreadShape shape, RandomSource random) {
        double horizontal = horizontalDistance(x, z, shape, radius);
        double norm = radius <= 0 ? 0.0 : horizontal / radius;
        return switch (distribution) {
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

    /**
     * Angular canister spread: writes yaw/pitch offsets (degrees) into {@code out[0]}/{@code out[1]}.
     */
    public static void sampleCanisterAngular(RandomSource random, RVP_EnumSpreadDistribution distribution,
                                             RVP_EnumSpreadShape footprint, float halfExtent, float[] out) {
        samplePlane(random, distribution, footprint, halfExtent, out);
    }

    /**
     * Rectangular grid angular spread ({@code canister_shape: square}).
     * {@code gridCell} is {@code {col, row}} from {@link RVP_CanisterGridLayout#assignCells(int, RVP_EnumSpreadDistribution)}.
     */
    public static void sampleCanisterGridAngular(int[] gridCell, int pelletCount,
                                                   float halfExtent, float[] out) {
        float[] unit = unitGridCenter(gridCell, pelletCount);
        out[0] = unit[0] * halfExtent;
        out[1] = unit[1] * halfExtent;
    }

    /**
     * Position canister spread (type 0): independent offsets on X/Y/Z in [-halfExtent, halfExtent].
     */
    public static void sampleCanisterPosition(RandomSource random, RVP_EnumSpreadDistribution distribution,
                                              RVP_EnumSpreadShape footprint, float halfExtent, float[] out) {
        samplePlane(random, distribution, footprint, halfExtent, out);
        out[2] = sampleAxis(random, distribution, halfExtent);
    }

    /**
     * Rectangular grid position spread ({@code canister_shape: square}).
     */
    public static void sampleCanisterGridPosition(int[] gridCell, int pelletCount,
                                                  float halfExtent, float[] out) {
        float[] unit = unitGridCenter(gridCell, pelletCount);
        out[0] = unit[0] * halfExtent;
        out[1] = unit[1] * halfExtent;
        out[2] = 0f;
    }

    private static float[] unitGridCenter(int[] gridCell, int pelletCount) {
        RVP_CanisterGridLayout.Dimensions dim = RVP_CanisterGridLayout.dimensionsFor(pelletCount);
        float[] uv = new float[2];
        RVP_CanisterGridLayout.unitCenter(gridCell[0], gridCell[1], dim, uv);
        return uv;
    }

    /**
     * @return true if this distribution can use fast uniform shuffle subsampling (dispenser).
     */
    public static boolean isUniformSubsample(RVP_EnumSpreadDistribution distribution) {
        return distribution == RVP_EnumSpreadDistribution.UNIFORM;
    }

    public static boolean isCenterClusterSubsample(RVP_EnumSpreadDistribution distribution) {
        return distribution == RVP_EnumSpreadDistribution.NORMAL
                || distribution == RVP_EnumSpreadDistribution.CLUSTER_CENTER;
    }

    private static void samplePlane(RandomSource random, RVP_EnumSpreadDistribution distribution,
                                    RVP_EnumSpreadShape footprint, float halfExtent, float[] out) {
        float[] unit = sampleUnitPlane(random, distribution, footprint);
        out[0] = unit[0] * halfExtent;
        out[1] = unit[1] * halfExtent;
    }

    private static float[] sampleUnitPlane(RandomSource random, RVP_EnumSpreadDistribution distribution,
                                           RVP_EnumSpreadShape footprint) {
        return switch (distribution) {
            case UNIFORM -> sampleUniformUnit(random, footprint);
            case NORMAL, CLUSTER_CENTER -> sampleGaussianUnit(random, footprint,
                    distribution == RVP_EnumSpreadDistribution.CLUSTER_CENTER ? 0.45f : 0.65f);
            case CLUSTER_EDGE -> sampleEdgeBiasedUnit(random, footprint);
            case RING -> sampleRingUnit(random, footprint);
        };
    }

    private static float[] sampleUniformUnit(RandomSource random, RVP_EnumSpreadShape footprint) {
        if (footprint == RVP_EnumSpreadShape.SQUARE) {
            return new float[]{random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f};
        }
        double theta = random.nextDouble() * Math.PI * 2.0;
        double r = Math.sqrt(random.nextDouble());
        return new float[]{(float) (r * Math.cos(theta)), (float) (r * Math.sin(theta))};
    }

    private static float[] sampleGaussianUnit(RandomSource random, RVP_EnumSpreadShape footprint, float sigma) {
        for (int i = 0; i < MAX_REJECTION_ATTEMPTS; i++) {
            float u = (float) random.nextGaussian() * sigma;
            float v = (float) random.nextGaussian() * sigma;
            u = Mth.clamp(u, -1f, 1f);
            v = Mth.clamp(v, -1f, 1f);
            if (footprint == RVP_EnumSpreadShape.SQUARE || u * u + v * v <= 1f) {
                return new float[]{u, v};
            }
        }
        return new float[]{0f, 0f};
    }

    private static float[] sampleEdgeBiasedUnit(RandomSource random, RVP_EnumSpreadShape footprint) {
        for (int i = 0; i < MAX_REJECTION_ATTEMPTS; i++) {
            float[] uv = sampleUniformUnit(random, footprint);
            float r = (float) Math.sqrt(uv[0] * uv[0] + uv[1] * uv[1]);
            if (r < 0.55f && random.nextFloat() > 0.15f) {
                continue;
            }
            return uv;
        }
        return new float[]{0f, 0f};
    }

    private static float[] sampleRingUnit(RandomSource random, RVP_EnumSpreadShape footprint) {
        for (int i = 0; i < MAX_REJECTION_ATTEMPTS; i++) {
            float[] uv = sampleUniformUnit(random, footprint);
            float r = (float) Math.sqrt(uv[0] * uv[0] + uv[1] * uv[1]);
            if (Math.abs(r - 0.7f) < 0.18f) {
                return uv;
            }
        }
        return sampleUniformUnit(random, footprint);
    }

    private static float sampleAxis(RandomSource random, RVP_EnumSpreadDistribution distribution, float halfExtent) {
        float unit = switch (distribution) {
            case UNIFORM -> random.nextFloat() * 2f - 1f;
            case NORMAL, CLUSTER_CENTER -> Mth.clamp((float) random.nextGaussian() * 0.65f, -1f, 1f);
            case CLUSTER_EDGE -> {
                float u = random.nextFloat() * 2f - 1f;
                if (Math.abs(u) < 0.55f && random.nextFloat() > 0.15f) {
                    u = Math.copySign(0.55f + random.nextFloat() * 0.45f, u == 0f ? 1f : u);
                }
                yield u;
            }
            case RING -> {
                float sign = random.nextBoolean() ? 1f : -1f;
                yield sign * (0.7f + (random.nextFloat() - 0.5f) * 0.18f);
            }
        };
        return unit * halfExtent;
    }

    private static double horizontalDistance(int x, int z, RVP_EnumSpreadShape shape, int radius) {
        return switch (shape) {
            case SQUARE, CUBE, DIAMOND -> Math.max(Math.abs(x), Math.abs(z));
            default -> Math.sqrt(x * x + z * z);
        };
    }
}
