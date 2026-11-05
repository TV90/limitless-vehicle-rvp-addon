package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.firecontrol.RVP_ManualSightInput;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = LocalVehiclePlayer.class, remap = false)
public class LocalVehiclePlayerMachinegunLeadTurnMixin {

    /** 本体用于区分进入瞄具后的自动对齐与真实鼠标输入；读取它可避免把自动对齐误记为微调。 */
    @Shadow
    public boolean mouseTurnedAfterScope;

    @Redirect(
            method = "handlePlayerTurn",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;setXAimRot(F)V"
            ),
            remap = false
    )
    private void ywzj_rvp$suppressStableLeadMouseX(WeaponUnit weaponUnit, float xAimRot) {
        if (RVP_ManualSightInput.suppressed(weaponUnit)) {
            return;
        }
        // 调用共用输入策略，旧站保持原有全自动抑制和半自动微调行为。
        RVP_ManualSightInput.recordPitch(weaponUnit, xAimRot, mouseTurnedAfterScope);
        weaponUnit.setXAimRot(xAimRot);
    }

    @Redirect(
            method = "handlePlayerTurn",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;setYAimRot(F)V"
            ),
            remap = false
    )
    private void ywzj_rvp$suppressStableLeadMouseY(WeaponUnit weaponUnit, float yAimRot) {
        if (RVP_ManualSightInput.suppressed(weaponUnit)) {
            return;
        }
        // 调用同一策略，在目标写入前锁存偏航微调。
        RVP_ManualSightInput.recordYaw(weaponUnit, yAimRot, mouseTurnedAfterScope);
        weaponUnit.setYAimRot(yAimRot);
    }

}
