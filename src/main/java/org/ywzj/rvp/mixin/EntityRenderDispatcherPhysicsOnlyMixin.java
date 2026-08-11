package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.vehicle.client.render.util.OBBRenderer;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.OBB;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.List;

/**
 * F3+B 调试碰撞箱渲染：把“仅物理”碰撞箱以灰色线框补画出来。
 * <p>
 * 仅物理盒在 {@link RVP_PhysicsOnlyCollisionHelper#rebuildPhysicsOnlyCubes} 时已从
 * {@code vehicle.getVehicleCubeOBBs()} 剔除，因此碰撞箱俯视图 UI 与本体的 F3+B 车体盒
 * 渲染都不会再画它们；这里在本体 {@code EntityRenderDispatcher.renderHitbox} RETURN 后
 * 追加一份灰色线框，便于开发/调试时区分“仅物理”碰撞体积。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherPhysicsOnlyMixin {

    @Inject(method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
            at = @At("RETURN"))
    private static void rvp$renderPhysicsOnlyHitboxes(PoseStack matrixStack, VertexConsumer buffer, Entity entity,
            float partialTicks, CallbackInfo ci) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        List<VehicleCubeOBB> physicsOnlyCubes = RVP_PhysicsOnlyCollisionHelper.getPhysicsOnlyCubes(vehicle);
        if (physicsOnlyCubes.isEmpty()) {
            return;
        }
        List<OBB> obbs = physicsOnlyCubes.stream().map(VehicleCubeOBB::obb).toList();
        // 灰色线框，区别于本体的绿色车体盒 / 红色武器盒
        OBBRenderer.INSTANCE.render(entity.position(), obbs, matrixStack, buffer,
                0.5f, 0.5f, 0.5f, 1.0f);
    }
}
