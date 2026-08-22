package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataARM;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataHITL;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataSACLOS;
import org.ywzj.rvp.weapon.data.RVP_TerminalGuidanceData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.util.List;

/** Resolves immutable launch-time and active-phase snapshots from the new schema. */
public final class RVP_GuidanceModelResolver {

    private RVP_GuidanceModelResolver() {}

    public static RVP_GuidanceLaunchConfig resolveLaunch(RVP_WeaponData weaponData) {
        return resolveLaunch(weaponData == null ? null : weaponData.getGuidanceData());
    }

    public static RVP_GuidanceLaunchConfig resolveLaunch(RVP_GuidanceData guidance) {
        RVP_GuidanceData data = guidance == null ? new RVP_GuidanceData() : guidance;
        // 未配置 max_off_axis_lock_angle 的武器：门控兜底用 max_guidance_angle（单侧）
        Integer offAxis = data.getMaxOffAxisLockAngle();
        int resolvedOffAxis = offAxis != null ? offAxis : data.getMaxGuidanceAngle();
        return new RVP_GuidanceLaunchConfig(
                data.getGuidanceType(),
                data.getLockTargetDistanceRange(),
                data.getLockAltitudeRange(),
                data.isEnableIrHmd(),
                data.getMaxLockAngle(),
                resolvedOffAxis,
                data.getLockAngleGate()
        );
    }

    public static RVP_GuidanceActiveConfig resolveActive(
            RVP_WeaponData weaponData,
            RVP_GuidancePhase phase
    ) {
        return resolveActive(weaponData == null ? null : weaponData.getGuidanceData(), phase);
    }

    public static RVP_GuidanceActiveConfig resolveActive(
            RVP_GuidanceData guidance,
            RVP_GuidancePhase phase
    ) {
        RVP_GuidanceData data = guidance == null ? new RVP_GuidanceData() : guidance;
        if (phase == RVP_GuidancePhase.TERMINAL && data.getTerminalGuidance() != null) {
            return resolveTerminal(data, data.getTerminalGuidance());
        }
        return resolveMain(data);
    }

    private static RVP_GuidanceActiveConfig resolveMain(RVP_GuidanceData data) {
        RVP_GuidanceDataGPS gps = data instanceof RVP_GuidanceDataGPS value ? value : null;
        RVP_GuidanceDataHITL hitl = data instanceof RVP_GuidanceDataHITL value ? value : null;
        RVP_GuidanceDataARM arm = data instanceof RVP_GuidanceDataARM value ? value : null;
        RVP_GuidanceDataSACLOS saclos = data instanceof RVP_GuidanceDataSACLOS value ? value : null;
        return new RVP_GuidanceActiveConfig(
                RVP_GuidancePhase.MAIN,
                data.getGuidanceType(),
                data.getGuidanceTickRange(),
                data.getGuidanceTargetDistanceRange(),
                data.getGuidanceAltitudeRange(),
                data.getMaxLockAngle(),
                data.getMaxGuidanceAngle(),
                data.getScanIntervalTick(),
                data.isPredictTargetPos(),
                data.getPredictTargetPosGain(),
                data.getMaxLateralAccel(),
                data.getPredictTargetPosStartTick(),
                data.getTopAttackHeight(),
                data.getCruiseStartTick(),
                data.getCruiseEndHorizontalDist(),
                data.getCruiseGravityScale(),
                data.getCruiseLevelingFactor(),
                data.getGuidanceAngleGate(),
                data.getAngleGateLockOutTick(),
                data.getActiveRadarActivationRange(),
                data.isEnableInertialGuidance(),
                false,
                false,
                0f,
                0f,
                false,
                0f,
                arm == null ? 0 : arm.getRadiationPulseMemoryTick(),
                arm == null ? 0 : arm.getArmMemoryTick(),
                arm == null ? 0f : arm.getArmLockedEmitterBonus(),
                gps == null ? 0f : gps.getGpsSpreadRadius(),
                hitl == null ? 0 : hitl.getHitlMaxTurnDegPerTick(),
                hitl == null ? "RADIO" : hitl.getSignalSource(),
                hitl == null ? 0 : hitl.getHitlMaxControlDist(),
                hitl == null ? 0 : hitl.getHitlMaxControlTick(),
                hitl == null ? 0 : hitl.getHitlMaxLookOffset(),
                hitl == null ? List.of() : hitl.getHitlVideoModes(),
                saclos != null && saclos.isSemiCorrectionEnabled(),
                saclos == null ? 0.05f : saclos.getSemiCorrectionStiffness(),
                saclos == null ? 0.05f : saclos.getSemiCorrectionDamping(),
                saclos == null ? 0.5f : saclos.getSemiCorrectionWobble(),
                RVP_PresetBallisticProfile.of(data),
                data.getInterferenceData() == null ? 8 : data.getInterferenceData().getSeekerJamLimit(),
                data.getInterferenceData() == null ? 1.0f : data.getInterferenceData().getSeekerFovShrinkFactor(),
                data.getInterferenceData() == null ? null : data.getInterferenceData().getSeekerShutOffTime(),
                data.getInterferenceData() == null ? 0.5f : data.getInterferenceData().getChaffResistance()
        );
    }

    private static RVP_GuidanceActiveConfig resolveTerminal(
            RVP_GuidanceData main,
            RVP_TerminalGuidanceData terminal
    ) {
        RVP_GuidanceDataARM arm = main instanceof RVP_GuidanceDataARM value ? value : null;
        return new RVP_GuidanceActiveConfig(
                RVP_GuidancePhase.TERMINAL,
                terminal.getGuidanceType(),
                null,
                terminal.getGuidanceTargetDistanceRange(),
                terminal.getGuidanceAltitudeRange(),
                terminal.getMaxLockAngle(),
                terminal.getMaxGuidanceAngle(),
                terminal.getScanIntervalTick(),
                terminal.isPredictTargetPos(),
                main.getPredictTargetPosGain(),
                main.getMaxLateralAccel(),
                main.getPredictTargetPosStartTick(),
                terminal.getTopAttackHeight(),
                null,
                0f,
                1f,
                0f,
                terminal.getGuidanceAngleGate(),
                terminal.getAngleGateLockOutTick(),
                terminal.getActiveRadarActivationRange(),
                terminal.isEnableInertialGuidance(),
                false,
                false,
                0f,
                0f,
                false,
                0f,
                arm == null ? 0 : arm.getRadiationPulseMemoryTick(),
                arm == null ? 0 : arm.getArmMemoryTick(),
                arm == null ? 0f : arm.getArmLockedEmitterBonus(),
                0f,
                0,
                "RADIO",
                0,
                0,
                0,
                List.of(),
                false,
                0.05f,
                0.05f,
                0.5f,
                RVP_PresetBallisticProfile.inactive(),
                main.getInterferenceData() == null ? 8 : main.getInterferenceData().getSeekerJamLimit(),
                main.getInterferenceData() == null ? 1.0f : main.getInterferenceData().getSeekerFovShrinkFactor(),
                main.getInterferenceData() == null ? null : main.getInterferenceData().getSeekerShutOffTime(),
                main.getInterferenceData() == null ? 0.5f : main.getInterferenceData().getChaffResistance()
        );
    }
}
