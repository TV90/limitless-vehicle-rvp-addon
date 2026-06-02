package org.ywzj.rvp.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/util/VectorUtil;worldToScreen(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            require = 0,
            remap = false
    )
    private Vec3 ywzj_rvp$lockCrosshairToCenter(Vec3 pos) {
        if (!ywzj_rvp$isTVMissileFirstPerson()) {
            return VectorUtil.worldToScreen(pos);
        }
        Minecraft mc = Minecraft.getInstance();
        return new Vec3(mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0, 0.0);
    }

    @Redirect(
            method = "renderHelicopter",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;getYRot()F"),
            require = 0,
            remap = false
    )
    private float ywzj_rvp$lockOpticalSightYaw(WeaponUnit weaponUnit) {
        if (ywzj_rvp$isTVMissileFirstPerson()) {
            return 0f;
        }
        return weaponUnit.getYRot();
    }

    @Redirect(
            method = "renderHelicopter",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;getXRot()F"),
            require = 0,
            remap = false
    )
    private float ywzj_rvp$lockOpticalSightPitch(WeaponUnit weaponUnit) {
        if (ywzj_rvp$isTVMissileFirstPerson()) {
            return -140f;
        }
        return weaponUnit.getXRot();
    }

    private static boolean ywzj_rvp$isTVMissileFirstPerson() {
        if (!RvpClientTVMissileState.isActive()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.options.getCameraType().isFirstPerson();
    }
}
