package org.ywzj.rvp.weapon.spread;

import org.ywzj.rvp.weapon.data.RVP_EnumSpreadDistribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rectangular pellet grid for {@link org.ywzj.rvp.weapon.data.RVP_FireData} canister spread
 * ({@code canister_shape: square}). E.g. 16 pellets → 4×4, 12 → 4×3.
 */
public final class RVP_CanisterGridLayout {

    private RVP_CanisterGridLayout() {}

    public record Dimensions(int cols, int rows) {
        public int capacity() {
            return cols * rows;
        }
    }

    /** One grid slot per pellet; {@code cells[i] = {col, row}} in column-major row order after distribution sort. */
    public static int[][] assignCells(int pelletCount, RVP_EnumSpreadDistribution distribution) {
        int n = Math.max(pelletCount, 1);
        Dimensions dim = dimensionsFor(n);
        int[][] cells = new int[n][2];
        List<Cell> ranked = rankCells(dim, distribution);
        for (int i = 0; i < n; i++) {
            Cell cell = ranked.get(i);
            cells[i][0] = cell.col;
            cells[i][1] = cell.row;
        }
        return cells;
    }

    /** Normalized cell center on [-1, 1] for column {@code col} / row {@code row}. */
    public static void unitCenter(int col, int row, Dimensions dim, float[] outUv) {
        outUv[0] = normalizedAxis(col, dim.cols);
        outUv[1] = normalizedAxis(row, dim.rows);
    }

    public static Dimensions dimensionsFor(int pelletCount) {
        int n = Math.max(pelletCount, 1);
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        return new Dimensions(cols, rows);
    }

    private static float normalizedAxis(int index, int size) {
        if (size <= 1) {
            return 0f;
        }
        return ((index + 0.5f) / size) * 2f - 1f;
    }

    private static List<Cell> rankCells(Dimensions dim, RVP_EnumSpreadDistribution distribution) {
        List<Cell> cells = new ArrayList<>(dim.capacity());
        for (int row = 0; row < dim.rows; row++) {
            for (int col = 0; col < dim.cols; col++) {
                float u = normalizedAxis(col, dim.cols);
                float v = normalizedAxis(row, dim.rows);
                cells.add(new Cell(col, row, cellPriority(distribution, u, v)));
            }
        }
        if (distribution == RVP_EnumSpreadDistribution.UNIFORM) {
            cells.sort(Comparator.comparingInt((Cell c) -> c.row).thenComparingInt(c -> c.col));
        } else {
            cells.sort((a, b) -> {
                int byPriority = Float.compare(b.priority, a.priority);
                if (byPriority != 0) {
                    return byPriority;
                }
                return a.row != b.row ? Integer.compare(a.row, b.row) : Integer.compare(a.col, b.col);
            });
        }
        return cells;
    }

    /**
     * Square footprint metric: Chebyshev distance from bore center on the unit grid.
     */
    private static float cellPriority(RVP_EnumSpreadDistribution distribution, float u, float v) {
        float norm = Math.max(Math.abs(u), Math.abs(v));
        return switch (distribution) {
            case UNIFORM -> 1f;
            case NORMAL -> (float) Math.exp(-norm * norm * 2.0);
            case CLUSTER_CENTER -> (float) Math.exp(-norm * norm * 5.0);
            case CLUSTER_EDGE -> norm < 0.55f ? 0.15f : (float) Math.pow(norm, 1.5);
            case RING -> {
                float band = 0.18f;
                float d = Math.abs(norm - 0.7f);
                yield d < band ? 1.0f : 0.08f;
            }
        };
    }

    private record Cell(int col, int row, float priority) {}
}
