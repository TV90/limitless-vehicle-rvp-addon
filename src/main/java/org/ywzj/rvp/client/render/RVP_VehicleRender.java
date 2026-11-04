package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleVisualRenderer;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteViewDistance;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;

/**
 * RVP 通用载具渲染器：在 {@link VehicleRender} 的完整渲染管线之后追加 RVP 自有视觉层。
 */
public class RVP_VehicleRender<T extends AbstractVehicle> extends VehicleRender<T> {

    public RVP_VehicleRender(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(T vehicle, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        // 调用本帧交接名单，远距计划已接管时不再绘制真实载具及其附加视觉层。
        if (!RVP_ClientRemoteViewDistance.allowsNativeVehicle(vehicle)
                || RVP_RemoteVehicleVisualRenderer.ownsLoadedEntity(vehicle)) {
            return;
        }
        super.render(vehicle, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        // 调用光电干扰机玻片夜间红光：姿势栈已回到实体原点，复用本帧已写入的动画姿态。
        RVP_EoJammerGlowRenderer.render(vehicle, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public boolean shouldRender(T vehicle, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
        // 调用同一交接名单，原生实体通道与 DH 真实载具保护层都会跳过已接管目标。
        return RVP_ClientRemoteViewDistance.allowsNativeVehicle(vehicle)
                && !RVP_RemoteVehicleVisualRenderer.ownsLoadedEntity(vehicle)
                && super.shouldRender(vehicle, frustum, cameraX, cameraY, cameraZ);
    }
}
