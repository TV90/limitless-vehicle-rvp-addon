package org.ywzj.rvp.weapon.seeker;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceStageData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;
import java.util.Optional;

/**
 * Seeker HUD eligibility and seeker geometry from RVP weapon JSON.
 */
public final class RVP_SeekerWeaponUtil {

    public record CockpitSeekerStage(int index, RVP_GuidanceStageData stage, RVP_EnumGuidanceType mode) {}

    private RVP_SeekerWeaponUtil() {}

    /**
     * Cockpit seeker HUD while the weapon is selected (pre-launch).
     * False when an earlier stage uses SACLOS/MCLOS at tick 0 (e.g. laser demo missile).
     */
    public static boolean preLaunchSeekerHudActive(RVP_WeaponData data) {
        if (!hasCockpitSeekerStage(data)) {
            return false;
        }
        return resolveCockpitSeekerStage(data)
                .map(stage -> !isBlockedByEarlierPreLaunchStage(data, stage.index()))
                .orElse(false);
    }

    public static boolean hasCockpitSeekerStage(RVP_WeaponData data) {
        return resolveCockpitSeekerStage(data).isPresent();
    }

    public static Optional<CockpitSeekerStage> resolveCockpitSeekerStage(RVP_WeaponData data) {
        if (data == null || data.getWeaponKind() != RVP_EnumWeaponKind.MISSILE) {
            return Optional.empty();
        }
        List<RVP_GuidanceStageData> stages = data.getGuidanceData().getStages();
        for (int i = 0; i < stages.size(); i++) {
            RVP_GuidanceStageData stage = stages.get(i);
            RVP_EnumGuidanceType mode = seekerStageMode(stage);
            if (mode == RVP_EnumGuidanceType.IR || mode == RVP_EnumGuidanceType.SARH) {
                return Optional.of(new CockpitSeekerStage(i, stage, mode));
            }
        }
        return Optional.empty();
    }

    public static RVP_EnumGuidanceType seekerMode(RVP_WeaponData data) {
        return resolveCockpitSeekerStage(data)
                .map(CockpitSeekerStage::mode)
                .orElse(RVP_EnumGuidanceType.NONE);
    }

    public static RVP_EnumGuidanceType seekerStageMode(RVP_GuidanceStageData stage) {
        if (stage == null) {
            return RVP_EnumGuidanceType.NONE;
        }
        boolean sarh = stage.getSources().stream()
                .anyMatch(source -> source.getType() == RVP_EnumGuidanceType.SARH);
        if (sarh) {
            return RVP_EnumGuidanceType.SARH;
        }
        boolean ir = stage.getSources().stream()
                .anyMatch(source -> source.getType() == RVP_EnumGuidanceType.IR);
        if (ir) {
            return RVP_EnumGuidanceType.IR;
        }
        return RVP_EnumGuidanceType.NONE;
    }

    public static RVP_GuidanceSeekerData resolveSeeker(RVP_WeaponData data) {
        return resolveCockpitSeekerStage(data)
                .map(stage -> {
                    if (!stage.stage().getSeeker().isEmpty()) {
                        return stage.stage().getSeeker().copy();
                    }
                    return data.resolveLaunchSeeker();
                })
                .orElseGet(data::resolveLaunchSeeker);
    }

    public static float seekerFov(RVP_WeaponData data) {
        return resolveSeeker(data).resolvedFov();
    }

    public static float seekerRange(RVP_WeaponData data) {
        return resolveSeeker(data).resolvedRange();
    }

    public static int seekerLockAcquireTicks(RVP_WeaponData data) {
        return resolveSeeker(data).resolvedLockAcquireTick();
    }

    public static int seekerLockCoolingTicks(RVP_WeaponData data) {
        return resolveSeeker(data).resolvedLockCoolingTick();
    }

    /**
     * Whether cockpit seeker lock survives a shot. JSON override wins; else SARH {@code true}, IR {@code false}.
     */
    public static boolean seekerRetainLockAfterFire(RVP_WeaponData data) {
        RVP_GuidanceSeekerData seeker = resolveSeeker(data);
        if (seeker.hasRetainLockAfterFire()) {
            return seeker.isRetainLockAfterFire();
        }
        return seekerMode(data) == RVP_EnumGuidanceType.SARH;
    }

    private static boolean isBlockedByEarlierPreLaunchStage(RVP_WeaponData data, int cockpitIndex) {
        List<RVP_GuidanceStageData> stages = data.getGuidanceData().getStages();
        for (int i = 0; i < cockpitIndex; i++) {
            if (blocksPreLaunchSeekerAtLaunch(stages.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocksPreLaunchSeekerAtLaunch(RVP_GuidanceStageData stage) {
        RVP_GuidanceActivationData activation = stage.getActivation();
        if (activation.getStartTick() > 0) {
            return false;
        }
        return stage.getSources().stream().anyMatch(source -> {
            RVP_EnumGuidanceType type = source.getType();
            return type == RVP_EnumGuidanceType.SACLOS || type == RVP_EnumGuidanceType.MCLOS;
        });
    }

    /** Fire-control radar on the operator station (e.g. Mi-28 {@code sighting_system}). */
    public static RadarUnit resolveFireControlRadar(WeaponUnit operatorUnit) {
        if (operatorUnit == null) {
            return null;
        }
        RadarUnit radar = operatorUnit.getMainRadarUnit();
        if (radar != null) {
            return radar;
        }
        for (WeaponUnit sub : operatorUnit.getSubWeaponUnits()) {
            radar = sub.getMainRadarUnit();
            if (radar != null) {
                return radar;
            }
        }
        return null;
    }
}
