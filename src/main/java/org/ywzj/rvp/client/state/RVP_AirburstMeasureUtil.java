package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 可编程空爆测距：以第一人称弹道落点（{@link WeaponUnit#weaponHitPos} /
 * {@link WeaponUnit#aimHitPosition()}）为准，与 {@code VehicleScopeOverlay} 绿框下 “XX m” 一致。
 */
public final class RVP_AirburstMeasureUtil {

    private RVP_AirburstMeasureUtil() {}

    /**
     * @return 测距米数；无有效落点时返回 0
     */
    public static int measureDistanceMeters(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return 0;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp.getPlayer() == null) {
            return 0;
        }

        Vec3 hit = weaponUnit.weaponHitPos;
        if (hit == null) {
            hit = weaponUnit.aimHitPosition();
        }
        if (hit == null) {
            return 0;
        }

        // 与 scope 落点测距 HUD（rangeFinding → aimLocationDistance）同一套斜距
        return (int) Math.round(lvp.getPlayer().position().distanceTo(hit));
    }
}
