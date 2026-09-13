package org.ywzj.rvp.firesupport.delivery;

import java.lang.reflect.Field;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_TerminalGuidanceData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 空射航点策略选择、GPS 固定释放几何与载机速度继承回归。 */
class RVP_AirLaunchedProjectileDeliveryTest {
    @Test
    void routePlannerSelectsGpsForPrimaryAndTerminalGuidanceButBallisticForUnguidedWeapons() {
        RVP_WeaponData primaryGps = weapon(RVP_EnumGuidanceType.GPS, null);
        RVP_WeaponData terminalGps = weapon(RVP_EnumGuidanceType.NONE, RVP_EnumGuidanceType.GPS);
        RVP_WeaponData unguided = weapon(RVP_EnumGuidanceType.NONE, null);

        assertTrue(RVP_AirLaunchedProjectileDelivery.selectRoutePlanner(primaryGps)
                instanceof RVP_GpsAirstrikeRoutePlanningStrategy);
        assertTrue(RVP_AirLaunchedProjectileDelivery.selectRoutePlanner(terminalGps)
                instanceof RVP_GpsAirstrikeRoutePlanningStrategy);
        assertTrue(RVP_AirLaunchedProjectileDelivery.selectRoutePlanner(unguided)
                instanceof RVP_BallisticAirstrikeRoutePlanningStrategy);
        assertFalse(RVP_AirLaunchedProjectileDelivery.selectRoutePlanner(unguided)
                instanceof RVP_GpsAirstrikeRoutePlanningStrategy);
    }

    @Test
    void gpsReleasePointUsesFixedUpstreamDistanceAndConfiguredAltitude() {
        Vec3 direction = new Vec3(0.6D, 0.0D, 0.8D);

        Vec3 release = RVP_GpsAirstrikeRoutePlanningStrategy.resolveReleasePosition(
                1000.0D, -200.0D, 72.0D, 256.0D, direction, 768.0D);

        assertEquals(539.2D, release.x, 1.0E-9D);
        assertEquals(328.0D, release.y, 1.0E-9D);
        assertEquals(-814.4D, release.z, 1.0E-9D);
    }

    @Test
    void gpsReferencePlannerRejectsAltitudeBelowMinimumWithDiagnostic() {
        RVP_AirstrikeRoutePlanningResult result =
                RVP_GpsAirstrikeRoutePlanningStrategy.planReference(
                        100.0D, 200.0D, 64.0D,
                        256.0D, 31.0D, 32.0D,
                        new Vec3(1.0D, 0.0D, 0.0D), 768.0D, 2.5D,
                        ignored -> true);

        assertFalse(result.successful());
        assertTrue(result.diagnostic().contains("GPS 空投释放高度低于最小值"));
    }

    @Test
    void gpsReferencePlannerRejectsOutOfBorderFixedReleaseWithoutShorteningDistance() {
        RVP_AirstrikeRoutePlanningResult result =
                RVP_GpsAirstrikeRoutePlanningStrategy.planReference(
                        100.0D, 200.0D, 64.0D,
                        256.0D, 256.0D, 32.0D,
                        new Vec3(1.0D, 0.0D, 0.0D), 768.0D, 2.5D,
                        ignored -> false);

        assertFalse(result.successful());
        assertTrue(result.diagnostic().contains("GPS 空投固定前置释放点超出世界边界"));
        assertTrue(result.diagnostic().contains("gpsReleaseDistance=768.0"));
    }

    @Test
    void gpsReferencePlannerReturnsConfiguredMotionAndClampedAltitude() {
        RVP_AirstrikeRoutePlanningResult result =
                RVP_GpsAirstrikeRoutePlanningStrategy.planReference(
                        1000.0D, -200.0D, 72.0D,
                        300.0D, 256.0D, 32.0D,
                        new Vec3(0.6D, 0.0D, 0.8D), 768.0D, 2.5D,
                        ignored -> true);

        assertTrue(result.successful());
        assertNotNull(result.plan());
        assertEquals(539.2D, result.plan().spawn().x, 1.0E-9D);
        assertEquals(328.0D, result.plan().spawn().y, 1.0E-9D);
        assertEquals(-814.4D, result.plan().spawn().z, 1.0E-9D);
        assertEquals(1.5D, result.plan().motion().x, 1.0E-9D);
        assertEquals(0.0D, result.plan().motion().y, 1.0E-9D);
        assertEquals(2.0D, result.plan().motion().z, 1.0E-9D);
        assertEquals(256.0D, result.plan().releaseAltitudeMeters());
        assertEquals(new Vec3(0.6D, 0.0D, 0.8D), result.plan().preferredInboundDirection(),
                "GPS 单发计划应把入场方向传给任务级参考航线");
    }

    @Test
    void gpsInitialMotionPreservesActualCarrierMotionIncludingVerticalComponent() {
        Vec3 carrierMotion = new Vec3(2.1D, 0.35D, -0.7D);

        Vec3 resolved = RVP_GpsAirstrikeRoutePlanningStrategy.resolveInitialMotion(
                carrierMotion, new Vec3(0.0D, 0.0D, 1.0D), 2.5D);

        assertEquals(carrierMotion, resolved);
    }

    @Test
    void gpsInitialMotionFallsBackToConfiguredInboundSpeedForMissingInvalidOrZeroMotion() {
        Vec3 direction = new Vec3(3.0D, 4.0D, 4.0D);
        Vec3 expected = new Vec3(1.5D, 0.0D, 2.0D);

        assertEquals(expected, RVP_GpsAirstrikeRoutePlanningStrategy.resolveInitialMotion(
                null, direction, 2.5D));
        assertEquals(expected, RVP_GpsAirstrikeRoutePlanningStrategy.resolveInitialMotion(
                Vec3.ZERO, direction, 2.5D));
        assertEquals(expected, RVP_GpsAirstrikeRoutePlanningStrategy.resolveInitialMotion(
                new Vec3(Double.NaN, 0.0D, 0.0D), direction, 2.5D));
    }

    /** 仅注入当前 schema 的主/末段制导字段，避免测试夹具触发本体 Forge 注册对象构造。 */
    private static RVP_WeaponData weapon(RVP_EnumGuidanceType primary, RVP_EnumGuidanceType terminal) {
        RVP_WeaponData weapon = allocateWithoutVehicleRegistry();
        RVP_GuidanceData guidance = new RVP_GuidanceData();
        set(guidance, "guidanceType", primary);
        if (terminal != null) {
            RVP_TerminalGuidanceData terminalGuidance = new RVP_TerminalGuidanceData();
            set(terminalGuidance, "guidanceType", terminal);
            set(guidance, "terminalGuidance", terminalGuidance);
        }
        set(weapon, "guidanceData", guidance);
        return weapon;
    }

    /** 为测试精确写入当前数据类私有字段。 */
    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    /** 绕过本体武器默认物品字段的 Forge 注册表构造，仅用于能力判断夹具。 */
    private static RVP_WeaponData allocateWithoutVehicleRegistry() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (RVP_WeaponData) ((sun.misc.Unsafe) field.get(null)).allocateInstance(RVP_WeaponData.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
