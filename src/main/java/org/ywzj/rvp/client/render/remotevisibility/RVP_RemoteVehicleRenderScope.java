package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFog.FogParameters;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.ProjectionPlan;

/** 用 try-with-resources 封闭远距载具批次的投影与雾状态。 */
final class RVP_RemoteVehicleRenderScope implements AutoCloseable {
    /** 实际访问 RenderSystem 的客户端状态后端。 */
    private static final RenderStateAccess RENDER_SYSTEM_ACCESS = new RenderSystemStateAccess();
    /** 进入远距批次前的完整雾参数。 */
    private final FogParameters originalFog;
    /** 当前作用域使用的状态访问后端。 */
    private final RenderStateAccess stateAccess;
    /** 是否已压入 RenderSystem 投影备份。 */
    private boolean projectionBackedUp;
    /** 是否已应用远距批次雾参数。 */
    private boolean fogApplied;
    /** 是否已经完成恢复，防止重复关闭。 */
    private boolean closed;

    private RVP_RemoteVehicleRenderScope(FogParameters originalFog, RenderStateAccess stateAccess) {
        this.originalFog = originalFog;
        this.stateAccess = stateAccess;
    }

    /** 保存状态，并应用计划中的扩展投影及环境允许的远距雾终点。 */
    static RVP_RemoteVehicleRenderScope open(ProjectionPlan plan, Camera camera) {
        RenderSystem.assertOnRenderThread();
        return open(plan, hasRestrictedVisibility(camera), false, RENDER_SYSTEM_ACCESS);
    }

    /** 保存状态，并按调用方要求强制应用计划投影，供跨渲染阶段的离屏目标使用。 */
    static RVP_RemoteVehicleRenderScope open(ProjectionPlan plan, Camera camera,
                                             boolean forceProjection) {
        RenderSystem.assertOnRenderThread();
        return open(plan, hasRestrictedVisibility(camera), forceProjection, RENDER_SYSTEM_ACCESS);
    }

    /** 使用指定状态后端建立作用域，供无 OpenGL 上下文的状态恢复测试复用。 */
    static RVP_RemoteVehicleRenderScope open(ProjectionPlan plan, boolean restrictedVisibility,
                                             RenderStateAccess stateAccess) {
        return open(plan, restrictedVisibility, false, stateAccess);
    }

    /** 使用指定状态后端和强制投影选项建立作用域，供离屏路径及无 GL 测试复用。 */
    static RVP_RemoteVehicleRenderScope open(ProjectionPlan plan, boolean restrictedVisibility,
                                             boolean forceProjection,
                                             RenderStateAccess stateAccess) {
        FogParameters originalFog = stateAccess.captureFog();
        RVP_RemoteVehicleRenderScope scope =
                new RVP_RemoteVehicleRenderScope(originalFog, stateAccess);
        try {
            if (plan.extended() || forceProjection) {
                stateAccess.backupProjection();
                scope.projectionBackedUp = true;
                stateAccess.applyProjection(plan.projection());
            }

            // 调用 RVP 雾策略，只在普通空气环境中延长完全雾化距离。
            FogParameters remoteFog = RVP_RemoteVehicleFog.forRemotePass(
                    originalFog, plan.requiredFarPlane(), restrictedVisibility);
            if (!remoteFog.equals(originalFog)) {
                scope.fogApplied = true;
                stateAccess.applyFog(remoteFog);
            }
            return scope;
        } catch (RuntimeException | Error exception) {
            scope.close();
            throw exception;
        }
    }

    /** 水下、熔岩、细雪、失明和黑暗均保留原版可见性限制。 */
    private static boolean hasRestrictedVisibility(Camera camera) {
        if (camera.getFluidInCamera() != FogType.NONE) {
            return true;
        }
        Entity cameraEntity = camera.getEntity();
        return cameraEntity instanceof LivingEntity livingEntity
                && (livingEntity.hasEffect(MobEffects.BLINDNESS)
                || livingEntity.hasEffect(MobEffects.DARKNESS));
    }

    /** 无论正常返回还是绘制异常，都恢复进入批次前的全部状态。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (fogApplied) {
                stateAccess.applyFog(originalFog);
            }
        } finally {
            if (projectionBackedUp) {
                stateAccess.restoreProjection();
            }
        }
    }

    /** 投影与完整雾参数的最小状态访问接口。 */
    interface RenderStateAccess {
        /** 捕获当前完整雾状态。 */
        FogParameters captureFog();

        /** 备份当前投影。 */
        void backupProjection();

        /** 应用远距投影。 */
        void applyProjection(org.joml.Matrix4f projection);

        /** 应用一组完整雾参数。 */
        void applyFog(FogParameters fog);

        /** 恢复此前备份的投影。 */
        void restoreProjection();
    }

    /** 生产环境对 RenderSystem 的直接状态访问实现。 */
    private static final class RenderSystemStateAccess implements RenderStateAccess {
        /** 捕获当前着色器的雾起止、颜色和形状。 */
        @Override
        public FogParameters captureFog() {
            float[] color = RenderSystem.getShaderFogColor();
            FogShape shape = RenderSystem.getShaderFogShape();
            return new FogParameters(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                    color[0], color[1], color[2], color[3], shape);
        }

        /** 调用 RenderSystem 投影栈，保存当前世界投影。 */
        @Override
        public void backupProjection() {
            RenderSystem.backupProjectionMatrix();
        }

        /** 调用 RenderSystem，只在远距载具批次中应用扩展投影。 */
        @Override
        public void applyProjection(org.joml.Matrix4f projection) {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        }

        /** 应用完整雾参数，避免只恢复终点而泄漏其他状态。 */
        @Override
        public void applyFog(FogParameters fog) {
            RenderSystem.setShaderFogStart(fog.start());
            RenderSystem.setShaderFogEnd(fog.end());
            RenderSystem.setShaderFogColor(fog.red(), fog.green(), fog.blue(), fog.alpha());
            RenderSystem.setShaderFogShape(fog.shape());
        }

        /** 调用 RenderSystem 投影栈恢复进入批次前的世界投影。 */
        @Override
        public void restoreProjection() {
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
