package org.ywzj.rvp.client.render.remotevisibility;

import org.junit.jupiter.api.Test;
import org.joml.Vector3f;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleBillboardManager.RenderMode;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleBillboardManager.SnapshotState;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState.RenderPolicy;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleBillboardSource;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleSnapshotWarmupMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RemoteVehicleBillboardManagerTest {
    @Test
    void forceAllOverridesValidLodAndAggressiveOnlyTargetsMissingLod() {
        RenderPolicy aggressive = policy(true, false, RemoteVehicleSnapshotWarmupMode.HIDE);
        RenderPolicy forceAll = policy(false, true, RemoteVehicleSnapshotWarmupMode.HIDE);
        RenderPolicy disabled = policy(false, false, RemoteVehicleSnapshotWarmupMode.HIDE);

        assertTrue(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(aggressive, false));
        assertFalse(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(aggressive, true));
        assertTrue(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(forceAll, true));
        assertFalse(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(disabled, false));
    }

    @Test
    void scopeZoomModelPreferenceOnlyOverridesBillboardDecision() {
        RenderPolicy aggressive = policy(true, false, RemoteVehicleSnapshotWarmupMode.HIDE);
        RenderPolicy forceAll = policy(false, true, RemoteVehicleSnapshotWarmupMode.HIDE);

        assertFalse(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(
                aggressive, false, true));
        assertFalse(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(
                forceAll, true, true));
        assertTrue(RVP_RemoteVehicleBillboardManager.shouldUseBillboard(
                aggressive, false, false));
    }

    @Test
    void warmupModeHandlesSlotAvailabilityAndHighModelBudget() {
        assertEquals(RenderMode.DYNAMIC_PENDING_HIDE,
                RVP_RemoteVehicleBillboardManager.decidePendingMode(
                        RemoteVehicleSnapshotWarmupMode.HIDE, true));
        assertEquals(RenderMode.DYNAMIC_PENDING_SLOT,
                RVP_RemoteVehicleBillboardManager.decidePendingMode(
                        RemoteVehicleSnapshotWarmupMode.SLOT_TEXTURE, true));
        assertEquals(RenderMode.DYNAMIC_PENDING_HIDE,
                RVP_RemoteVehicleBillboardManager.decidePendingMode(
                        RemoteVehicleSnapshotWarmupMode.SLOT_TEXTURE, false));
        assertEquals(RenderMode.DYNAMIC_PENDING_MODEL,
                RVP_RemoteVehicleBillboardManager.decidePendingMode(
                        RemoteVehicleSnapshotWarmupMode.MODEL, false));

        assertTrue(RVP_RemoteVehicleBillboardManager.usesHighModelBudget(
                RenderMode.DYNAMIC_PENDING_MODEL));
        assertTrue(RVP_RemoteVehicleBillboardManager.usesHighModelBudget(
                RenderMode.HIGH_MODEL_FALLBACK));
        assertFalse(RVP_RemoteVehicleBillboardManager.usesHighModelBudget(
                RenderMode.DYNAMIC_PENDING_HIDE));
        assertFalse(RVP_RemoteVehicleBillboardManager.usesHighModelBudget(
                RenderMode.DYNAMIC_READY));
    }

    @Test
    void sourceAndSnapshotAvailabilityChooseExpectedFallbacks() {
        RenderPolicy slotSource = new RenderPolicy(
                true, false, RemoteVehicleBillboardSource.SLOT_TEXTURE,
                RemoteVehicleSnapshotWarmupMode.HIDE, 25);
        assertEquals(RenderMode.SLOT_TEXTURE,
                RVP_RemoteVehicleBillboardManager.decideRenderMode(
                        slotSource, false, true, SnapshotState.MISSING));
        assertEquals(RenderMode.HIGH_MODEL_FALLBACK,
                RVP_RemoteVehicleBillboardManager.decideRenderMode(
                        slotSource, false, false, SnapshotState.MISSING));

        RenderPolicy dynamicSource = policy(true, false, RemoteVehicleSnapshotWarmupMode.HIDE);
        assertEquals(RenderMode.DYNAMIC_READY,
                RVP_RemoteVehicleBillboardManager.decideRenderMode(
                        dynamicSource, false, false, SnapshotState.READY));
        assertEquals(RenderMode.DYNAMIC_PENDING_HIDE,
                RVP_RemoteVehicleBillboardManager.decideRenderMode(
                        dynamicSource, false, false, SnapshotState.MISSING));
        assertEquals(RenderMode.HIGH_MODEL_FALLBACK,
                RVP_RemoteVehicleBillboardManager.decideRenderMode(
                        dynamicSource, false, false, SnapshotState.FAILED));
        assertEquals(RenderMode.NORMAL_MODEL,
                RVP_RemoteVehicleBillboardManager.decideRenderMode(
                        dynamicSource, true, true, SnapshotState.READY));
    }

    @Test
    void angleBucketsWrapYawAndClampPitch() {
        assertEquals(0, RVP_RemoteVehicleBillboardManager.quantizeYaw(0.0F));
        assertEquals(0, RVP_RemoteVehicleBillboardManager.quantizeYaw(360.0F));
        assertEquals(15, RVP_RemoteVehicleBillboardManager.quantizeYaw(-22.5F));
        assertEquals(8, RVP_RemoteVehicleBillboardManager.quantizeYaw(180.0F));

        assertEquals(-4, RVP_RemoteVehicleBillboardManager.quantizePitch(-120.0F));
        assertEquals(0, RVP_RemoteVehicleBillboardManager.quantizePitch(0.0F));
        assertEquals(4, RVP_RemoteVehicleBillboardManager.quantizePitch(120.0F));
        assertEquals(2, RVP_RemoteVehicleBillboardManager.quantizePitch(44.0F));
    }

    @Test
    void snapshotCameraKeepsModelToObserverDirection() {
        Vector3f levelFront = RVP_RemoteVehicleBillboardManager.directionForBuckets(0, 0);
        assertEquals(0.0F, levelFront.x, 1.0E-6F);
        assertEquals(0.0F, levelFront.y, 1.0E-6F);
        assertEquals(1.0F, levelFront.z, 1.0E-6F);

        Vector3f elevatedFront = RVP_RemoteVehicleBillboardManager.directionForBuckets(0, 2);
        assertTrue(elevatedFront.y > 0.0F);
        assertTrue(elevatedFront.z > 0.0F);
    }

    @Test
    void renderTargetTextureUsesBottomOriginWithoutFlippingResourceTextures() {
        assertEquals(0.0F, RVP_RemoteVehicleBillboardManager.resolveTextureV(true, false));
        assertEquals(1.0F, RVP_RemoteVehicleBillboardManager.resolveTextureV(true, true));
        assertEquals(1.0F, RVP_RemoteVehicleBillboardManager.resolveTextureV(false, false));
        assertEquals(0.0F, RVP_RemoteVehicleBillboardManager.resolveTextureV(false, true));
    }

    @Test
    void renderTargetTextureCorrectsHorizontalHandednessWithoutFlippingResourceTextures() {
        assertEquals(1.0F, RVP_RemoteVehicleBillboardManager.resolveTextureU(true, false));
        assertEquals(0.0F, RVP_RemoteVehicleBillboardManager.resolveTextureU(true, true));
        assertEquals(0.0F, RVP_RemoteVehicleBillboardManager.resolveTextureU(false, false));
        assertEquals(1.0F, RVP_RemoteVehicleBillboardManager.resolveTextureU(false, true));
    }

    @Test
    void cacheDefaultsStayBoundedAndEmptyWithoutGpuGeneration() {
        assertEquals(256, RVP_RemoteVehicleBillboardManager.SNAPSHOT_SIZE);
        assertEquals(128, RVP_RemoteVehicleBillboardManager.MAX_SNAPSHOT_CACHE_ENTRIES);
        assertEquals(0, RVP_RemoteVehicleBillboardManager.snapshotCacheSize());
    }

    /** 创建指定策略开关的服务端渲染策略。 */
    private static RenderPolicy policy(boolean aggressive, boolean forceAll,
                                       RemoteVehicleSnapshotWarmupMode warmupMode) {
        return new RenderPolicy(
                aggressive,
                forceAll,
                RemoteVehicleBillboardSource.DYNAMIC_SNAPSHOT,
                warmupMode, 25);
    }
}
