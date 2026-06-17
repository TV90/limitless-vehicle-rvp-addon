package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.vehicle.client.gui.VehicleAimAtOverlay;
import org.ywzj.vehicle.client.render.util.GuiHelper;

/**
 * IR HMD 激活时，屏蔽原版 IR 15px 固定圈（由 HMD overlay 替代绘制）。
 */
@Mixin(value = VehicleAimAtOverlay.class, remap = false)
public class VehicleAimAtOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lorg/ywzj/vehicle/client/render/util/GuiHelper;drawCircle(Lcom/mojang/blaze3d/vertex/PoseStack;FFFIFFF)V", remap = false)
    )
    private void ywzj_rvp$redirectIrCircle(PoseStack poseStack, float x, float y, float radius, int color, float thickness, float startAngle, float endAngle) {
        // 只屏蔽 IR 15px 小圈（thickness=0.03f 唯一标识 IR 小圈）
        if (RVP_ClientHmdState.getInstance().isIrHmd() && Math.abs(thickness - 0.03f) < 0.001f) {
            return;
        }
        GuiHelper.drawCircle(poseStack, x, y, radius, color, thickness, startAngle, endAngle);
    }
}
