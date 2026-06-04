package org.ywzj.rvp.weapon.data;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * Footprint of a dispenser spread relative to the impact center.
 */
public enum RVP_EnumDispenserSpreadShape implements StringRepresentable {
    /** Horizontal disk ({@code x² + z² ≤ r²}), vertical extent from {@code y_radius}. */
    CIRCLE("circle"),
    /** Horizontal axis-aligned square (Chebyshev on XZ). */
    SQUARE("square"),
    /** 3D sphere. */
    SPHERE("sphere"),
    /** 3D axis-aligned cube. */
    CUBE("cube"),
    /** Horizontal disk extruded along Y (same as circle with explicit {@code y_radius}). */
    CYLINDER("cylinder"),
    /** Manhattan ({@code |x|+|y|+|z| ≤ r}) octahedron. */
    DIAMOND("diamond");

    private final String id;

    RVP_EnumDispenserSpreadShape(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public static RVP_EnumDispenserSpreadShape fromString(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return CIRCLE;
        }
        String key = raw.trim().toLowerCase();
        for (RVP_EnumDispenserSpreadShape shape : values()) {
            if (shape.id.equals(key)) {
                return shape;
            }
        }
        return CIRCLE;
    }

    public boolean contains(int x, int y, int z, int radius, int yRadius) {
        int yr = Math.max(yRadius, 0);
        return switch (this) {
            case CIRCLE, CYLINDER -> x * x + z * z <= radius * radius && Math.abs(y) <= yr;
            case SQUARE -> Math.abs(x) <= radius && Math.abs(z) <= radius && Math.abs(y) <= yr;
            case SPHERE -> x * x + y * y + z * z <= radius * radius;
            case CUBE -> Math.abs(x) <= radius && Math.abs(y) <= radius && Math.abs(z) <= radius;
            case DIAMOND -> Math.abs(x) + Math.abs(y) + Math.abs(z) <= radius;
        };
    }

    /** Whether this shape uses a separate vertical half-extent instead of {@code radius} on Y. */
    public boolean usesYRadius() {
        return this == CIRCLE || this == SQUARE || this == CYLINDER;
    }
}
