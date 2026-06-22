package org.ywzj.rvp.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.client.state.RVP_ClientHitlCamera;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.client.handler.FirstPersonHandler;

/**
 * Welds the first-person camera to the missile seeker lens (body axis, BF3/4 TV style).
 * Falls back to last pose if the entity is briefly missing.
 *
 * <p>Priority is raised above the base {@code ywzj_vehicle} {@code CameraMixin} (default 1000)
 * so this injector runs at {@code TAIL} after the launcher SCOPE camera is applied.</p>
 */
@Mixin(value = Camera.class, priority = 1500)
public abstract class CameraTVMissileMixin {

    @Shadow
    private Vec3 position;

    @Shadow
    private BlockPos.MutableBlockPos blockPosition;

    @Shadow
    private Vector3f forwards;

    @Shadow
    private Vector3f up;

    @Shadow
    private Vector3f left;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    private Quaternionf rotation;

    @Inject(method = "setup", at = @At("TAIL"))
    private void ywzj_rvp$RVP_HitlMissileCamera(BlockGetter pLevel, Entity pEntity, boolean pDetached,
                                                boolean pThirdPersonReverse, float pPartialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!RVP_ClientHitlState.isActive() || mc.level == null || mc.player == null) {
            RVP_ClientHitlCamera.reset();
            return;
        }
        if (pEntity != mc.player) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }

        float[] pose = null;
        Entity e = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
        if (e instanceof RVP_MissileEntity missile && !e.isRemoved()) {
            pose = RVP_ClientHitlCamera.updateSmoothedPose(missile, pPartialTick);
        } else if (RVP_ClientHitlCamera.hasPose()) {
            pose = RVP_ClientHitlCamera.getPose();
        }
        if (pose == null) {
            return;
        }

        ywzj_rvp$setPosition(pose[2], pose[3], pose[4]);
        ywzj_rvp$setRotation(pose[0], pose[1]);
        FirstPersonHandler.zRot = 0f;
    }

    private void ywzj_rvp$setPosition(double x, double y, double z) {
        Vec3 pos = new Vec3(x, y, z);
        this.position = pos;
        this.blockPosition.set(x, y, z);
    }

    private void ywzj_rvp$setRotation(float yRot, float xRot) {
        this.xRot = xRot;
        this.yRot = yRot;
        this.rotation.rotationYXZ(-yRot * Mth.DEG_TO_RAD, xRot * Mth.DEG_TO_RAD, 0.0F);
        this.forwards.set(0.0F, 0.0F, 1.0F).rotate(this.rotation);
        this.up.set(0.0F, 1.0F, 0.0F).rotate(this.rotation);
        this.left.set(1.0F, 0.0F, 0.0F).rotate(this.rotation);
    }
}
