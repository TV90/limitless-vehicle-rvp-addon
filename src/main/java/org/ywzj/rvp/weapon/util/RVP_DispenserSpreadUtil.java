package org.ywzj.rvp.weapon.util;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.weapon.data.RVP_DispenserPayloadData;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadDistribution;
import org.ywzj.rvp.weapon.data.RVP_EnumSpreadShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves which block offsets inside a configured spread shape should receive a placement attempt.
 */
public final class RVP_DispenserSpreadUtil {

    private static final int REJECTION_MAX_ATTEMPTS = 4096;

    private RVP_DispenserSpreadUtil() {}

    public record SpreadOffset(int x, int y, int z) {
        public BlockPos apply(BlockPos center) {
            return center.offset(x, y, z);
        }
    }

    /**
     * @return offsets to try (world positions = center + offset)
     */
    public static List<SpreadOffset> sampleOffsets(RVP_DispenserPayloadData payload, RandomSource random) {
        int radius = payload.getSpreadRadius();
        int yRadius = payload.resolveYRadius();
        RVP_EnumSpreadShape shape = payload.getSpreadShape();
        int density = payload.getDensity();

        List<SpreadOffset> candidates = enumerateCandidates(shape, radius, yRadius, payload.isSurfaceOnly());
        if (candidates.isEmpty()) {
            return List.of(new SpreadOffset(0, 0, 0));
        }

        int target = resolveTargetCount(candidates.size(), density);
        if (target >= candidates.size()) {
            return candidates;
        }

        return subsample(candidates, target, payload.getDistribution(), shape, radius, random);
    }

    static int resolveTargetCount(int candidateCount, int density) {
        if (candidateCount <= 0) {
            return 0;
        }
        if (density >= 100) {
            return candidateCount;
        }
        if (density <= 1) {
            return 1;
        }
        int scaled = (int) Math.round(candidateCount * (density / 100.0));
        return Math.max(1, Math.min(scaled, candidateCount));
    }

    private static List<SpreadOffset> enumerateCandidates(RVP_EnumSpreadShape shape, int radius, int yRadius,
                                                          boolean surfaceOnly) {
        List<SpreadOffset> list = new ArrayList<>();
        if (surfaceOnly) {
            RVP_EnumSpreadShape horizontalShape = horizontalProjection(shape);
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (horizontalShape.contains(x, 0, z, radius, yRadius)) {
                        list.add(new SpreadOffset(x, 0, z));
                    }
                }
            }
            return list;
        }
        int yMin = -yRadius;
        int yMax = yRadius;
        if (shape == RVP_EnumSpreadShape.SPHERE || shape == RVP_EnumSpreadShape.CUBE
                || shape == RVP_EnumSpreadShape.DIAMOND) {
            yMin = -radius;
            yMax = radius;
        }
        for (int x = -radius; x <= radius; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (shape.contains(x, y, z, radius, yRadius)) {
                        list.add(new SpreadOffset(x, y, z));
                    }
                }
            }
        }
        return list;
    }

    private static RVP_EnumSpreadShape horizontalProjection(RVP_EnumSpreadShape shape) {
        return switch (shape) {
            case SQUARE, CUBE -> RVP_EnumSpreadShape.SQUARE;
            case DIAMOND -> RVP_EnumSpreadShape.DIAMOND;
            default -> RVP_EnumSpreadShape.CIRCLE;
        };
    }

    private static List<SpreadOffset> subsample(List<SpreadOffset> candidates, int target,
                                                RVP_EnumSpreadDistribution distribution,
                                                RVP_EnumSpreadShape shape, int radius,
                                                RandomSource random) {
        if (RVP_SpreadDistributionUtil.isUniformSubsample(distribution)) {
            List<SpreadOffset> copy = new ArrayList<>(candidates);
            for (int i = copy.size() - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                SpreadOffset tmp = copy.get(i);
                copy.set(i, copy.get(j));
                copy.set(j, tmp);
            }
            return copy.subList(0, target);
        }

        if (RVP_SpreadDistributionUtil.isCenterClusterSubsample(distribution)) {
            List<SpreadOffset> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(o -> o.x * o.x + o.y * o.y + o.z * o.z));
            return sorted.subList(0, target);
        }

        if (distribution == RVP_EnumSpreadDistribution.CLUSTER_EDGE) {
            List<SpreadOffset> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(
                    (SpreadOffset o) -> o.x * o.x + o.y * o.y + o.z * o.z).reversed());
            return sorted.subList(0, target);
        }

        return weightedSample(candidates, target, distribution, shape, radius, random);
    }

    private static List<SpreadOffset> weightedSample(List<SpreadOffset> candidates, int target,
                                                     RVP_EnumSpreadDistribution distribution,
                                                     RVP_EnumSpreadShape shape, int radius,
                                                     RandomSource random) {
        List<SpreadOffset> picked = new ArrayList<>(target);
        Set<Long> seen = new HashSet<>(target * 2);
        int attempts = 0;
        while (picked.size() < target && attempts < REJECTION_MAX_ATTEMPTS) {
            attempts++;
            SpreadOffset offset = candidates.get(random.nextInt(candidates.size()));
            long key = BlockPos.asLong(offset.x, offset.y, offset.z);
            if (!seen.add(key)) {
                continue;
            }
            float w = distribution.weight(offset.x, offset.y, offset.z, radius, shape, random);
            if (random.nextFloat() <= w) {
                picked.add(offset);
            } else {
                seen.remove(key);
            }
        }
        while (picked.size() < target) {
            SpreadOffset offset = candidates.get(random.nextInt(candidates.size()));
            long key = BlockPos.asLong(offset.x, offset.y, offset.z);
            if (seen.add(key)) {
                picked.add(offset);
            }
        }
        return picked;
    }

    public static boolean isWithinWorld(Level level, BlockPos pos) {
        return pos.getY() >= level.getMinBuildHeight() && pos.getY() < level.getMaxBuildHeight();
    }
}
