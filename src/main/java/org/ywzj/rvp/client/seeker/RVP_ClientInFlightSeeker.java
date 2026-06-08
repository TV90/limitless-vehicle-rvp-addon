package org.ywzj.rvp.client.seeker;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientSaclosGuidance;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;

import java.util.List;

/**
 * In-flight seeker HUD when cockpit seeker is deferred (e.g. laser then SARH).
 * Uses tick windows only — not full activation evaluator (illumination/target may lag on client).
 */
public final class RVP_ClientInFlightSeeker {

    private RVP_ClientInFlightSeeker() {}

    @Nullable
    public static RVP_BaseBullet findActiveMissile(LocalPlayer player, Level level) {
        if (player == null || level == null) {
            return null;
        }
        RVP_BaseBullet best = null;
        int youngestTick = Integer.MAX_VALUE;
        for (RVP_BaseBullet bullet : level.getEntitiesOfClass(
                RVP_BaseBullet.class, player.getBoundingBox().inflate(4096))) {
            if (!bullet.isAlive() || !isOperatorProjectile(bullet, player)) {
                continue;
            }
            if (!isInCockpitSeekerPhase(bullet)) {
                continue;
            }
            if (bullet.tickCount < youngestTick) {
                youngestTick = bullet.tickCount;
                best = bullet;
            }
        }
        return best;
    }

    public static boolean isInCockpitSeekerPhase(RVP_BaseBullet bullet) {
        RVP_WeaponData data = RVP_ClientSaclosGuidance.resolveWeaponData(bullet);
        if (data == null || RVP_SeekerWeaponUtil.preLaunchSeekerHudActive(data)) {
            return false;
        }
        return RVP_SeekerWeaponUtil.resolveCockpitSeekerStage(data)
                .map(stage -> isCockpitSeekerStageActiveByTick(bullet, data, stage))
                .orElse(false);
    }

    private static boolean isCockpitSeekerStageActiveByTick(
            RVP_BaseBullet bullet,
            RVP_WeaponData data,
            RVP_SeekerWeaponUtil.CockpitSeekerStage cockpit
    ) {
        int tick = bullet.tickCount;
        RVP_GuidanceActivationData activation = cockpit.stage().getActivation();
        if (tick < activation.getStartTick()) {
            return false;
        }
        if (activation.getEndTick() >= 0 && tick > activation.getEndTick()) {
            return false;
        }

        List<RVP_GuidanceStageData> stages = data.getGuidanceData().getStages();
        for (int i = cockpit.index() + 1; i < stages.size(); i++) {
            RVP_GuidanceStageData later = stages.get(i);
            if (!isStageActiveByTick(later, tick)) {
                continue;
            }
            RVP_EnumGuidanceType laterMode = RVP_SeekerWeaponUtil.seekerStageMode(later);
            if (laterMode == RVP_EnumGuidanceType.IR || laterMode == RVP_EnumGuidanceType.ARH) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStageActiveByTick(RVP_GuidanceStageData stage, int tick) {
        RVP_GuidanceActivationData activation = stage.getActivation();
        if (tick < activation.getStartTick()) {
            return false;
        }
        return activation.getEndTick() < 0 || tick <= activation.getEndTick();
    }

    private static boolean isOperatorProjectile(RVP_BaseBullet bullet, LocalPlayer player) {
        Entity owner = bullet.getOwner();
        if (owner == player) {
            return true;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null && owner == vehicle) {
            return true;
        }
        return vehicle != null && owner == null && bullet.distanceTo(vehicle) < 512.0D;
    }
}
