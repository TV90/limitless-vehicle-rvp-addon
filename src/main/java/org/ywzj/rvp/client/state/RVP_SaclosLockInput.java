package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * SACLOS: cockpit press-R toggles pod laser; HITL DESIGNATE press-R re-selects designated target.
 */
public final class RVP_SaclosLockInput {

    private RVP_SaclosLockInput() {}

    public static boolean tryConsumeLockKey(WeaponUnit weaponUnit) {
        if (RVP_ClientHitlState.isDesignateMode()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return true;
            }
            Entity entity = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
            if (entity instanceof RVP_MissileEntity missile) {
                RVP_ClientHitlState.redesignateTargetAtCrosshair(mc, missile);
            }
            return true;
        }
        if (!RVP_ClientSaclosState.isGuiding()) {
            return false;
        }
        RVP_ClientSaclosState.toggleLaser();
        return true;
    }
}
