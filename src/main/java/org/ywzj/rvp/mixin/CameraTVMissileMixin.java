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
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;

@Mixin(Camera.class)
public abstract class CameraTVMissileMixin {

    @Unique
    private static int ywzj_rvp$lastTVMissileId = -1;

    @Unique
    private static float ywzj_rvp$lastYaw;

    @Unique
    private static float ywzj_rvp$lastPitch;

    @Shadow
    protected abstract void setPosition(double pX, double pY, double pZ);

    @Shadow
    protected abstract void setRotation(float pYRot, float pXRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void ywzj_rvp$TVMissileEntityCamera(BlockGetter pLevel, Entity pEntity, boolean pDetached, boolean pThirdPersonReverse, float pPartialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!RvpClientTVMissileState.isActive() || mc.level == null || mc.player == null) {
            return;
        }
        if (pEntity != mc.player) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        Entity e = mc.level.getEntity(RvpClientTVMissileState.getActiveMissileId());
        if (!(e instanceof TVMissileEntity missile) || missile.isRemoved()) {
            return;
        }
        double x = Mth.lerp(pPartialTick, missile.xo, missile.getX());
        double y = Mth.lerp(pPartialTick, missile.yo, missile.getY());
        double z = Mth.lerp(pPartialTick, missile.zo, missile.getZ());
        float yRot = Mth.lerp(pPartialTick, missile.yRotO, missile.getYRot());
        float xRot = Mth.lerp(pPartialTick, missile.xRotO, missile.getXRot());
        Vec3 noseOffset = missile.getLookAngle().normalize().scale(1.2);
        x += noseOffset.x;
        y += noseOffset.y;
        z += noseOffset.z;

        int missileId = missile.getId();
        if (ywzj_rvp$lastTVMissileId != missileId) {
            ywzj_rvp$lastTVMissileId = missileId;
            ywzj_rvp$lastYaw = yRot;
            ywzj_rvp$lastPitch = xRot;
        } else {
            float dy = Mth.wrapDegrees(yRot - ywzj_rvp$lastYaw);
            float dx = xRot - ywzj_rvp$lastPitch;
            float factor = 0.1f;
            if (Math.abs(dy) <= 0.5f) {
                yRot = ywzj_rvp$lastYaw + dy * factor;
            }
            if (Math.abs(dx) <= 0.5f) {
                xRot = ywzj_rvp$lastPitch + dx * factor;
            }
            ywzj_rvp$lastYaw = yRot;
            ywzj_rvp$lastPitch = xRot;
        }

        setPosition(x, y, z);
        setRotation(yRot, xRot);
    }
}
