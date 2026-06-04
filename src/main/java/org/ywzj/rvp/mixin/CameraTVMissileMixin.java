package org.ywzj.rvp.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientTVMissileState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;
import org.ywzj.vehicle.util.VectorUtil;

/**
 * Welds the first-person camera to the controlled TV missile.
 *
 * <p>Priority is raised above the base {@code ywzj_vehicle} {@code CameraMixin} (default 1000)
 * so this injector is applied (and therefore runs at {@code TAIL}) AFTER the base one. The base
 * mixin moves the camera to the launcher optical-sight position while {@code viewType == SCOPE};
 * if it ran last it would override us and the camera would never follow the missile.
 */
@Mixin(value = Camera.class, priority = 1500)
public abstract class CameraTVMissileMixin {

    /** Time constant (seconds) of the camera rotation low-pass filter. Larger = smoother but laggier. */
    @Unique
    private static final float ywzj_rvp$ROTATION_SMOOTH_TAU = 0.12f;

    @Unique
    private static int ywzj_rvp$lastMissileId = -1;
    @Unique
    private static float ywzj_rvp$smoothYaw;
    @Unique
    private static float ywzj_rvp$smoothPitch;
    @Unique
    private static long ywzj_rvp$lastFrameNanos;

    @Shadow
    protected abstract void setPosition(double pX, double pY, double pZ);

    @Shadow
    protected abstract void setRotation(float pYRot, float pXRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void ywzj_rvp$RVP_TVMissileCamera(BlockGetter pLevel, Entity pEntity, boolean pDetached, boolean pThirdPersonReverse, float pPartialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!RVP_ClientTVMissileState.isActive() || mc.level == null || mc.player == null) {
            return;
        }
        if (pEntity != mc.player) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        Entity e = mc.level.getEntity(RVP_ClientTVMissileState.getActiveMissileId());
        if (!(e instanceof RVP_MissileEntity) || e.isRemoved()) {
            return;
        }
        double x = Mth.lerp(pPartialTick, e.xo, e.getX());
        double y = Mth.lerp(pPartialTick, e.yo, e.getY());
        double z = Mth.lerp(pPartialTick, e.zo, e.getZ());
        float targetYaw = Mth.rotLerp(pPartialTick, e.yRotO, e.getYRot());
        float targetPitch = Mth.lerp(pPartialTick, e.xRotO, e.getXRot());

        // The missile turns in coarse, discrete steps server-side (rate-limited deg/tick) and
        // its rotation arrives quantized over the network, so plain per-tick interpolation looks
        // jerky at the start/stop of a turn. Run a frame-rate-independent exponential low-pass
        // (critically damped feel) over the camera angles to smooth the view.
        long now = System.nanoTime();
        if (ywzj_rvp$lastMissileId != e.getId()) {
            ywzj_rvp$lastMissileId = e.getId();
            ywzj_rvp$smoothYaw = targetYaw;
            ywzj_rvp$smoothPitch = targetPitch;
        } else {
            float dtSeconds = Mth.clamp((now - ywzj_rvp$lastFrameNanos) / 1.0e9f, 0f, 0.1f);
            float alpha = 1f - (float) Math.exp(-dtSeconds / ywzj_rvp$ROTATION_SMOOTH_TAU);
            ywzj_rvp$smoothYaw = Mth.wrapDegrees(
                    ywzj_rvp$smoothYaw + Mth.wrapDegrees(targetYaw - ywzj_rvp$smoothYaw) * alpha);
            ywzj_rvp$smoothPitch += (targetPitch - ywzj_rvp$smoothPitch) * alpha;
        }
        ywzj_rvp$lastFrameNanos = now;

        float yRot = ywzj_rvp$smoothYaw;
        float xRot = ywzj_rvp$smoothPitch;

        // Sit slightly ahead of the (smoothed) nose so the missile body is not drawn over the lens.
        Vec3 noseOffset = VectorUtil.rotToVec(xRot, yRot).normalize().scale(1.2);
        x += noseOffset.x;
        y += noseOffset.y;
        z += noseOffset.z;

        setPosition(x, y, z);
        setRotation(yRot, xRot);
        // The base SCOPE camera leaves a launcher/vehicle roll in zRot; the missile view
        // must not inherit it, so flatten the horizon.
        FirstPersonHandler.zRot = 0f;
    }
}
