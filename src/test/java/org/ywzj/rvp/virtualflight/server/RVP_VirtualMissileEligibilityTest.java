package org.ywzj.rvp.virtualflight.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_TerminalGuidanceData;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_Range;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_VirtualMissileEligibilityTest {
    /** 准入测试只构造正式配置，不启动游戏或网络。 */
    @org.junit.jupiter.api.BeforeAll
    static void initialize() {
        org.ywzj.rvp.entity.projectile.RVP_ProjectileSimulationBootstrap.initialize();
    }
    private final Gson gson = new Gson();

    @Test
    void acceptsExplicitFixedGpsConfiguration() {
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), gps(), new RVP_FuseData()));
    }

    @Test
    void acceptsTerminalTopAttackAndPayloadConfigurationsForRuntimeRestoration() {
        RVP_GuidanceData terminal = gps();
        setField(terminal, "terminalGuidance", new RVP_TerminalGuidanceData());
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), terminal, new RVP_FuseData()));

        RVP_GuidanceData topAttack = gps();
        setField(topAttack, "topAttackHeight", 120f);
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), topAttack, new RVP_FuseData()));

        RVP_FuseData proximity = gson.fromJson("{\"proximity_radius\":8}", RVP_FuseData.class);
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), gps(), proximity));

        RVP_FuseData groundProximity = gson.fromJson("{\"ground_proximity_fuse_distance\":8}", RVP_FuseData.class);
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), gps(), groundProximity));
    }

    /** 高度门不再阻止整个弹种进入虚拟态；实际观测未知时由运行时拒绝该次高度判断。 */
    @Test
    void acceptsAltitudeEnvelopeWithAndWithoutPreset() {
        RVP_GuidanceData terrain = gps();
        // 调用生产区间构造器，建立真实目标离地高度门，而非用测试布尔量替代配置。
        setField(terrain, "guidanceAltitudeRange", RVP_Range.closed(0f, 100f));
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), terrain, new RVP_FuseData()));
        setField(terrain, "presetCruiseAltitude", 200f);
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), terrain, new RVP_FuseData()));
    }

    @Test
    void phaseBClampsTicketRadiusAndKeepsOneTickIntegration() {
        RVP_VirtualMidcourseData data = config(
                "{\"enabled\":true,\"restore_ticket_radius\":99,\"virtual_update_interval_tick\":20}");
        assertEquals(2, data.getRestoreTicketRadius());
        assertEquals(1, data.getVirtualUpdateIntervalTick());
    }

    /** 所有正式枚举均走同一准入，不按 GPS、电视、雷达或红外建立白名单。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RVP_EnumGuidanceType.class)
    void acceptsEveryGuidanceType(RVP_EnumGuidanceType type) {
        var guidance = gps();
        setField(guidance, "guidanceType", type);
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), guidance, new RVP_FuseData()));
    }

    @Test
    void explicitWeaponSwitchStillDefaultsToDisabled() {
        assertEquals(RVP_VirtualMissileEligibility.Result.DISABLED,
                evaluate(config("{}"), gps(), new RVP_FuseData()));
    }

    private static RVP_VirtualMissileEligibility.Result evaluate(
            RVP_VirtualMidcourseData config, RVP_GuidanceData guidance, RVP_FuseData fuse) {
        var data = new RVP_WeaponData();
        setField(data, "virtualMidcourseData", config);
        setField(data, "guidanceData", guidance);
        setField(data, "fuseData", fuse);
        // 调用完整生产准入入口，防止以后又从武器配置添加制导/引信白名单。
        return RVP_VirtualMissileEligibility.evaluateConfiguration(data);
    }

    private RVP_VirtualMidcourseData config(String json) {
        return gson.fromJson(json, RVP_VirtualMidcourseData.class);
    }

    private static RVP_GuidanceData gps() {
        RVP_GuidanceData guidance = new RVP_GuidanceData();
        setField(guidance, "guidanceType", RVP_EnumGuidanceType.GPS);
        return guidance;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
