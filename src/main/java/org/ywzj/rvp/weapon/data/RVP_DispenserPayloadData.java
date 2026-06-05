package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * MCH {@code DispenseItem} / {@code DispenseRange} parity. Any weapon with {@code dispenser_data}
 * may place/use items on impact; {@code rvp:dispenser} uses placement-only when configured.
 */
public class RVP_DispenserPayloadData {

    /** Item id to place/use, e.g. {@code minecraft:bone_meal}. */
    @SerializedName("item")
    private String item = "";

    @SerializedName("damage")
    private int damage = 0;

    /** Horizontal / primary spread radius in blocks (MCH {@code DispenseRange}). */
    @SerializedName(value = "place_radius", alternate = {"radius", "spread_radius"})
    private int placeRadius = 1;

    /**
     * Vertical half-height for {@link RVP_EnumSpreadShape#usesYRadius()} shapes;
     * {@code -1} uses shape-specific defaults.
     */
    @SerializedName("y_radius")
    private int yRadius = -1;

    /** Placement density 1–100: 100 = every cell in shape, 1 = a single cell. */
    @SerializedName("density")
    private int density = 100;

    @SerializedName("shape")
    private String shape = RVP_EnumSpreadShape.CIRCLE.getSerializedName();

    /** Spread density within {@link #shape}; see {@link RVP_EnumSpreadDistribution}. */
    @SerializedName("distribution")
    private String distribution = RVP_EnumSpreadDistribution.UNIFORM.getSerializedName();

    /**
     * When true (default), impact triggers {@link org.ywzj.rvp.weapon.effects.RVP_DispenserPlacement}
     * instead of only {@code explosion}.
     */
    @SerializedName("place_on_impact")
    private boolean placeOnImpact = true;

    /** If true, only attempt placement on blocks with air above (surface). */
    @SerializedName("surface_only")
    private boolean surfaceOnly = false;

    public boolean hasItem() {
        return item != null && !item.isBlank();
    }

    @Nullable
    public ResourceLocation itemId() {
        if (!hasItem()) {
            return null;
        }
        String raw = item.trim();
        if (raw.contains(":")) {
            return ResourceLocation.tryParse(raw);
        }
        return ResourceLocation.fromNamespaceAndPath("minecraft", raw);
    }

    public int getDamage() {
        return Math.max(damage, 0);
    }

    public int getPlaceRadius() {
        return getSpreadRadius();
    }

    public int getSpreadRadius() {
        return Mth.clamp(placeRadius, 1, 24);
    }

    public int resolveYRadius() {
        RVP_EnumSpreadShape spreadShape = getSpreadShape();
        if (spreadShape == RVP_EnumSpreadShape.SPHERE
                || spreadShape == RVP_EnumSpreadShape.CUBE
                || spreadShape == RVP_EnumSpreadShape.DIAMOND) {
            return getSpreadRadius();
        }
        if (yRadius >= 0) {
            return Mth.clamp(yRadius, 0, 24);
        }
        if (spreadShape == RVP_EnumSpreadShape.CYLINDER) {
            return getSpreadRadius();
        }
        return 0;
    }

    public int getDensity() {
        return Mth.clamp(density, 1, 100);
    }

    public RVP_EnumSpreadShape getSpreadShape() {
        return RVP_EnumSpreadShape.fromString(shape);
    }

    public RVP_EnumSpreadDistribution getDistribution() {
        return RVP_EnumSpreadDistribution.fromString(distribution);
    }

    public boolean isPlaceOnImpact() {
        return placeOnImpact;
    }

    public boolean isSurfaceOnly() {
        return surfaceOnly;
    }
}
