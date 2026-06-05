package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * Spread applied when spawning submunition payloads. Reuses the same sampling as
 * {@link RVP_FireData} canister ({@link org.ywzj.rvp.weapon.util.RVP_SpreadDistributionUtil}).
 */
public class RVP_SubmunitionSpreadData {

    /**
     * Simple axis velocity jitter (MCH {@code BombletDiff}). Used when {@link #canisterDiff} is 0
     * and {@link #mode} is {@code box}.
     */
    @SerializedName("box_spread")
    private float boxSpread = 0f;

    public void setBoxSpread(float boxSpread) {
        this.boxSpread = boxSpread;
    }

    /**
     * {@code box} = {@link #boxSpread} random cube; {@code canister} = angle/position canister
     * ({@link #canisterType}).
     */
    @SerializedName("mode")
    private String mode = "box";

    @SerializedName("canister_type")
    private int canisterType = 1;

    @SerializedName("canister_diff")
    private float canisterDiff = 0.3f;

    @SerializedName("canister_distribution")
    private String canisterDistribution = RVP_EnumSpreadDistribution.UNIFORM.getSerializedName();

    @SerializedName("canister_shape")
    private String canisterShape = RVP_EnumSpreadShape.CIRCLE.getSerializedName();

    public boolean usesCanister() {
        return "canister".equalsIgnoreCase(mode) || canisterDiff > 0f;
    }

    public float getBoxSpread() {
        return Math.max(boxSpread, 0f);
    }

    public int getCanisterType() {
        return Math.max(0, Math.min(canisterType, 2));
    }

    public float getCanisterDiff() {
        return Math.max(canisterDiff, 0f);
    }

    public RVP_EnumSpreadDistribution getCanisterDistribution() {
        return RVP_EnumSpreadDistribution.fromString(canisterDistribution);
    }

    public RVP_EnumSpreadShape getCanisterShape() {
        return RVP_EnumSpreadShape.forCanister(canisterShape);
    }
}
