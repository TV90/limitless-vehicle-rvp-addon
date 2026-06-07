package org.ywzj.rvp.client.state;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.util.VectorUtil;

/**
 * Camera welded to the missile body axis (BF TV style).
 *
 * <p>MOUSE: view hard-locked to missile forward.</p>
 * <p>DESIGNATE (SACLOS+TV): body axis with frame-rate exponential rotation smoothing.</p>
 */
public final class RVP_ClientHitlCamera {

    private static final float POSITION_TAU = 0.05f;
    private static final float VIEW_ROTATION_TAU = 0.038f;
    /** SACLOS+TV designate: softer follow than instant snap, tighter than passive VIEW. */
    private static final float DESIGNATE_ROTATION_TAU = 0.072f;
    private static final double NOSE_OFFSET = 0.35;

    private static int lastMissileId = -1;
    private static boolean poseValid;

    private static float seekerYaw;
    private static float seekerPitch;
    private static float displayYaw;
    private static float displayPitch;
    private static double smoothX;
    private static double smoothY;
    private static double smoothZ;
    private static long lastFrameNanos;

    private RVP_ClientHitlCamera() {}

    public static void reset() {
        lastMissileId = -1;
        poseValid = false;
        lastFrameNanos = 0L;
    }

    public static void onMissileAcquired(RVP_MissileEntity missile) {
        float yaw = missile.getYRot();
        float pitch = missile.getXRot();
        seekerYaw = yaw;
        seekerPitch = pitch;
        displayYaw = yaw;
        displayPitch = pitch;
        lastMissileId = missile.getId();
        poseValid = false;
        lastFrameNanos = 0L;
    }

    public static boolean hasPose() {
        return poseValid;
    }

    /** Body axis yaw (camera / seeker boresight). */
    public static float getBodyYaw() {
        return poseValid ? displayYaw : seekerYaw;
    }

    /** Body axis pitch (camera / seeker boresight). */
    public static float getBodyPitch() {
        return poseValid ? displayPitch : seekerPitch;
    }

    /** Crosshair aim yaw = body + mouse offset within seeker FOV. */
    public static float getAimYaw() {
        return Mth.wrapDegrees(getBodyYaw() + RVP_ClientHitlState.getLookOffsetYaw());
    }

    /** Crosshair aim pitch = body + mouse offset within seeker FOV. */
    public static float getAimPitch() {
        return Mth.clamp(getBodyPitch() + RVP_ClientHitlState.getLookOffsetPitch(), -89.9f, 89.9f);
    }

    public static float getSmoothedYaw() {
        return getBodyYaw();
    }

    public static float getSmoothedPitch() {
        return getBodyPitch();
    }

    public static Vec3 getSmoothedLensPosition() {
        return new Vec3(smoothX, smoothY, smoothZ);
    }

    public static float[] getPose() {
        return new float[] { displayYaw, displayPitch, (float) smoothX, (float) smoothY, (float) smoothZ };
    }

    public static void tickSeekerBody(RVP_MissileEntity missile) {
        if (lastMissileId != missile.getId()) {
            onMissileAcquired(missile);
        }
        seekerYaw = missile.getYRot();
        seekerPitch = missile.getXRot();
    }

    public static float[] updateSmoothedPose(RVP_MissileEntity missile, float partialTick) {
        if (lastMissileId != missile.getId()) {
            onMissileAcquired(missile);
        }

        long now = System.nanoTime();
        float dtSeconds = lastFrameNanos == 0L
                ? 0f
                : Mth.clamp((now - lastFrameNanos) / 1.0e9f, 0f, 0.1f);
        lastFrameNanos = now;
        RVP_ClientHitlState.tickDesignateLookOffset(dtSeconds);

        float bodyYaw = Mth.lerp(partialTick, missile.yRotO, missile.getYRot());
        float bodyPitch = Mth.lerp(partialTick, missile.xRotO, missile.getXRot());

        float orientYaw;
        float orientPitch;
        if (RVP_ClientHitlState.isMouseSteering()) {
            orientYaw = bodyYaw;
            orientPitch = bodyPitch;
            displayYaw = bodyYaw;
            displayPitch = bodyPitch;
        } else if (RVP_ClientHitlState.isDesignateMode()) {
            seekerYaw = bodyYaw;
            seekerPitch = bodyPitch;
            if (!poseValid || dtSeconds <= 0f) {
                displayYaw = bodyYaw;
                displayPitch = bodyPitch;
            } else {
                float rotAlpha = 1f - (float) Math.exp(-dtSeconds / DESIGNATE_ROTATION_TAU);
                displayYaw = Mth.wrapDegrees(displayYaw + Mth.wrapDegrees(bodyYaw - displayYaw) * rotAlpha);
                displayPitch += (bodyPitch - displayPitch) * rotAlpha;
            }
            orientYaw = displayYaw;
            orientPitch = displayPitch;
        } else {
            seekerYaw = bodyYaw;
            seekerPitch = bodyPitch;
            if (!poseValid || dtSeconds <= 0f) {
                displayYaw = bodyYaw;
                displayPitch = bodyPitch;
            } else {
                float rotAlpha = 1f - (float) Math.exp(-dtSeconds / VIEW_ROTATION_TAU);
                displayYaw = Mth.wrapDegrees(displayYaw + Mth.wrapDegrees(bodyYaw - displayYaw) * rotAlpha);
                displayPitch += (bodyPitch - displayPitch) * rotAlpha;
            }
            orientYaw = displayYaw;
            orientPitch = displayPitch;
        }

        double bodyX = Mth.lerp(partialTick, missile.xo, missile.getX());
        double bodyY = Mth.lerp(partialTick, missile.yo, missile.getY());
        double bodyZ = Mth.lerp(partialTick, missile.zo, missile.getZ());

        Vec3 noseOffset = VectorUtil.rotToVec(orientPitch, orientYaw).normalize().scale(NOSE_OFFSET);
        double targetX = bodyX + noseOffset.x;
        double targetY = bodyY + noseOffset.y;
        double targetZ = bodyZ + noseOffset.z;

        if (!poseValid) {
            smoothX = targetX;
            smoothY = targetY;
            smoothZ = targetZ;
        } else if (dtSeconds > 0f) {
            float posAlpha = 1f - (float) Math.exp(-dtSeconds / POSITION_TAU);
            smoothX += (targetX - smoothX) * posAlpha;
            smoothY += (targetY - smoothY) * posAlpha;
            smoothZ += (targetZ - smoothZ) * posAlpha;
        }
        poseValid = true;

        return new float[] { displayYaw, displayPitch, (float) smoothX, (float) smoothY, (float) smoothZ };
    }
}
