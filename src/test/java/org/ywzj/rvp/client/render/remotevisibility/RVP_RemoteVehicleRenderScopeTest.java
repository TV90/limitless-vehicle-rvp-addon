package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.shaders.FogShape;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFog.FogParameters;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.FarPlaneDemand;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.ProjectionPlan;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleRenderScope.RenderStateAccess;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RemoteVehicleRenderScopeTest {
    @Test
    void normalCloseRestoresProjectionAndAllFogParameters() {
        FakeRenderState state = new FakeRenderState();
        ProjectionPlan plan = extendedPlan();

        try (RVP_RemoteVehicleRenderScope ignored =
                     RVP_RemoteVehicleRenderScope.open(plan, false, state)) {
            assertTrue(state.remoteProjectionActive);
            assertEquals((float) plan.requiredFarPlane(), state.currentFog.end());
            assertEquals(state.originalFog.start(), state.currentFog.start());
            assertEquals(state.originalFog.shape(), state.currentFog.shape());
        }

        assertFalse(state.remoteProjectionActive);
        assertEquals(state.originalFog, state.currentFog);
        assertEquals(1, state.projectionRestoreCount);
    }

    @Test
    void exceptionalBodyStillClosesAndRestoresState() {
        FakeRenderState state = new FakeRenderState();

        assertThrows(IllegalStateException.class, () -> {
            try (RVP_RemoteVehicleRenderScope ignored =
                         RVP_RemoteVehicleRenderScope.open(extendedPlan(), false, state)) {
                throw new IllegalStateException("synthetic render failure");
            }
        });

        assertFalse(state.remoteProjectionActive);
        assertEquals(state.originalFog, state.currentFog);
        assertEquals(1, state.projectionRestoreCount);
    }

    @Test
    void partialFogApplyFailureRunsRollback() {
        FakeRenderState state = new FakeRenderState();
        state.failFirstFogApply = true;

        assertThrows(IllegalStateException.class,
                () -> RVP_RemoteVehicleRenderScope.open(extendedPlan(), false, state));

        assertFalse(state.remoteProjectionActive);
        assertEquals(state.originalFog, state.currentFog);
        assertEquals(1, state.projectionRestoreCount);
    }

    @Test
    void offscreenRouteCanForceUnextendedProjectionAndRestoreIt() {
        FakeRenderState state = new FakeRenderState();
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0D), 16.0F / 9.0F, 0.05F, 1_024.0F);
        ProjectionPlan plan = RVP_RemoteVehicleProjection.plan(projection,
                List.of(new FarPlaneDemand(500.0D, 5.0D))).orElseThrow();
        assertFalse(plan.extended());

        try (RVP_RemoteVehicleRenderScope ignored =
                     RVP_RemoteVehicleRenderScope.open(plan, false, true, state)) {
            assertTrue(state.remoteProjectionActive);
        }

        assertFalse(state.remoteProjectionActive);
        assertEquals(1, state.projectionRestoreCount);
    }

    /** 创建必定超过 1024 格原远平面的测试计划。 */
    private static ProjectionPlan extendedPlan() {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0D), 16.0F / 9.0F, 0.05F, 1_024.0F);
        return RVP_RemoteVehicleProjection.plan(projection,
                List.of(new FarPlaneDemand(2_000.0D, 10.0D))).orElseThrow();
    }

    /** 不访问 OpenGL 的渲染状态测试替身。 */
    private static final class FakeRenderState implements RenderStateAccess {
        /** 进入作用域前的完整雾状态。 */
        private final FogParameters originalFog = new FogParameters(64.0F, 256.0F,
                0.1F, 0.2F, 0.3F, 1.0F, FogShape.CYLINDER);
        /** 当前模拟雾状态。 */
        private FogParameters currentFog = originalFog;
        /** 是否处于远距投影。 */
        private boolean remoteProjectionActive;
        /** 是否让第一次雾应用在部分写入后抛出异常。 */
        private boolean failFirstFogApply;
        /** 投影恢复调用次数。 */
        private int projectionRestoreCount;

        /** 返回当前模拟雾状态。 */
        @Override
        public FogParameters captureFog() {
            return currentFog;
        }

        /** 测试替身无需另存矩阵，作用域会通过恢复入口验证配对。 */
        @Override
        public void backupProjection() {
        }

        /** 标记远距投影已经生效。 */
        @Override
        public void applyProjection(Matrix4f projection) {
            remoteProjectionActive = true;
        }

        /** 应用模拟雾参数，并可注入一次部分写入异常。 */
        @Override
        public void applyFog(FogParameters fog) {
            currentFog = fog;
            if (failFirstFogApply) {
                failFirstFogApply = false;
                throw new IllegalStateException("synthetic fog apply failure");
            }
        }

        /** 恢复原投影并记录配对次数。 */
        @Override
        public void restoreProjection() {
            remoteProjectionActive = false;
            projectionRestoreCount++;
        }
    }
}
