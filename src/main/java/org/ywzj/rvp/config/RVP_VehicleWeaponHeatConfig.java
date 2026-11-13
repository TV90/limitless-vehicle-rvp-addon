package org.ywzj.rvp.config;

public record RVP_VehicleWeaponHeatConfig(
        int heatCount,
        int maxHeatCount,
        int overheatExtraHeat,
        String heatLabel,
        String heatLabelCn,
        double heatDecayAccel
) {
    public static final RVP_VehicleWeaponHeatConfig DISABLED =
            new RVP_VehicleWeaponHeatConfig(0, 0, 30, "", "", 0.0);

    public boolean enabled() {
        return maxHeatCount > 0;
    }
}
