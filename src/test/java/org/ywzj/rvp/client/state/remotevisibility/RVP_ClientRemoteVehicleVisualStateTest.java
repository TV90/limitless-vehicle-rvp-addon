package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ClientRemoteVehicleVisualStateTest {
    /** 测试代理实体类型。 */
    private static final ResourceLocation ENTITY_TYPE = id("ywzj_vehicle:fixed_wing_vehicle");
    /** 测试车型数据。 */
    private static final ResourceLocation VEHICLE_ID = id("rvp:test_aircraft");
    /** 测试显示数据。 */
    private static final ResourceLocation DISPLAY_ID = id("rvp:test_aircraft_display");

    @Test
    void metadataChangeAndDestroyedRecoveryRequireRebuild() {
        var metadata = new RVP_ClientRemoteVehicleVisualState.ProxyMetadata(
                ENTITY_TYPE, VEHICLE_ID, DISPLAY_ID);
        var changedDisplay = new RVP_ClientRemoteVehicleVisualState.ProxyMetadata(
                ENTITY_TYPE, VEHICLE_ID, id("rvp:other_display"));

        assertFalse(RVP_ClientRemoteVehicleVisualState.requiresRebuild(
                metadata, sample(100L, false), metadata, sample(105L, false)));
        assertTrue(RVP_ClientRemoteVehicleVisualState.requiresRebuild(
                metadata, sample(100L, false), changedDisplay, sample(105L, false)));
        assertTrue(RVP_ClientRemoteVehicleVisualState.requiresRebuild(
                metadata, sample(100L, true), metadata, sample(105L, false)));
    }

    @Test
    void positionAndWrappedAnglesInterpolateAcrossTwoSamples() {
        var previous = sample(100L, false, new Vec3(0.0D, 10.0D, 0.0D), Vec3.ZERO,
                179.0F, -170.0F, 350.0F, 2.0D);
        var latest = sample(105L, false, new Vec3(10.0D, 20.0D, 30.0D), Vec3.ZERO,
                -179.0F, 170.0F, 10.0F, 12.0D);

        var result = RVP_ClientRemoteVehicleVisualState.interpolateSamples(previous, latest, 102.5D, 5.0D);

        assertEquals(new Vec3(5.0D, 15.0D, 15.0D), result.position());
        assertEquals(180.0F, result.xRot(), 0.0001F);
        assertEquals(-180.0F, result.yRot(), 0.0001F);
        assertEquals(360.0F, result.zRot(), 0.0001F);
        assertEquals(7.0D, result.heightAboveGround(), 0.0001D);
    }

    @Test
    void extrapolationAndRenderCursorAreCappedAtFiveTicks() {
        var previous = sample(100L, false);
        var latest = sample(105L, false, new Vec3(10.0D, 0.0D, 0.0D), new Vec3(2.0D, 0.0D, 0.0D),
                1.0F, 2.0F, 3.0F, 4.0D);

        double cursor = RVP_ClientRemoteVehicleVisualState.advanceRenderTime(108.0D, 106.0D, 105L, 5.0D);
        assertEquals(108.0D, cursor, 0.0001D);
        assertEquals(110.0D,
                RVP_ClientRemoteVehicleVisualState.advanceRenderTime(cursor, 999.0D, 105L, 5.0D),
                0.0001D);

        var result = RVP_ClientRemoteVehicleVisualState.interpolateSamples(previous, latest, 999.0D, 5.0D);
        assertEquals(new Vec3(20.0D, 0.0D, 0.0D), result.position());
        assertEquals(1.0F, result.xRot(), 0.0001F);
    }

    @Test
    void timeoutExpiresExactlyAtTwentyFiveTicks() {
        assertFalse(RVP_ClientRemoteVehicleVisualState.isExpired(100L, 124L));
        assertTrue(RVP_ClientRemoteVehicleVisualState.isExpired(100L, 125L));
    }

    @Test
    void sequenceMustIncreaseStrictly() {
        assertFalse(RVP_ClientRemoteVehicleVisualState.isNewerSequence(9L, 10L));
        assertFalse(RVP_ClientRemoteVehicleVisualState.isNewerSequence(10L, 10L));
        assertTrue(RVP_ClientRemoteVehicleVisualState.isNewerSequence(11L, 10L));
    }

    /** 创建只包含本测试所需字段的样本。 */
    private static RVP_ClientRemoteVehicleVisualState.Sample sample(long gameTime, boolean destroyed) {
        return sample(gameTime, destroyed, Vec3.ZERO, Vec3.ZERO,
                0.0F, 0.0F, 0.0F, 0.0D);
    }

    /** 创建指定姿态和运动的样本。 */
    private static RVP_ClientRemoteVehicleVisualState.Sample sample(
            long gameTime, boolean destroyed, Vec3 position, Vec3 velocity,
            float xRot, float yRot, float zRot, double heightAboveGround) {
        return new RVP_ClientRemoteVehicleVisualState.Sample(gameTime, 42, position, velocity,
                xRot, yRot, zRot, heightAboveGround, destroyed, true, 80.0F, 60.0F);
    }

    /** 解析测试资源 ID。 */
    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
