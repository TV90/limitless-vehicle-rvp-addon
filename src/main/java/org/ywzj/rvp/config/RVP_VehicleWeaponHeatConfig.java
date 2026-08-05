package org.ywzj.rvp.config;

public record RVP_VehicleWeaponHeatConfig(
        int heatCount,
        int maxHeatCount,
        int overheatExtraHeat
) {
    public static final RVP_VehicleWeaponHeatConfig DISABLED = new RVP_VehicleWeaponHeatConfig(0, 0, 30);

    public boolean enabled() {
        return maxHeatCount > 0;
    }
}
