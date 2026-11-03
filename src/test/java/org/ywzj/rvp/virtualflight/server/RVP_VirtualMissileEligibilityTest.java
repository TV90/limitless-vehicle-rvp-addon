package org.ywzj.rvp.virtualflight.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_TerminalGuidanceData;
import org.ywzj.rvp.weapon.data.RVP_VirtualMidcourseData;
import org.ywzj.rvp.weapon.data.RVP_Range;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_VirtualMissileEligibilityTest {
    private final Gson gson = new Gson();

    @Test
    void acceptsExplicitFixedGpsConfiguration() {
        assertEquals(RVP_VirtualMissileEligibility.Result.ELIGIBLE,
                evaluate(config("{\"enabled\":true}"), gps(), new RVP_FuseData()));
    }

    @Test
    void rejectsTerminalTopAttackAndWorldDependentFuse() {
        RVP_GuidanceData terminal = gps();
        setField(terminal, "terminalGuidance", new RVP_TerminalGuidanceData());
        assertEquals(RVP_VirtualMissileEligibility.Result.INELIGIBLE_TERMINAL_GUIDANCE,
                evaluate(config("{\"enabled\":true}"), terminal, new RVP_FuseData()));

        RVP_GuidanceData topAttack = gps();
        setField(topAttack, "topAttackHeight", 120f);
        assertEquals(RVP_VirtualMissileEligibility.Result.INELIGIBLE_TOP_ATTACK,
                evaluate(config("{\"enabled\":true}"), topAttack, new RVP_FuseData()));

        RVP_FuseData proximity = gson.fromJson("{\"proximity_radius\":8}", RVP_FuseData.class);
        assertEquals(RVP_VirtualMissileEligibility.Result.INELIGIBLE_WORLD_DEPENDENT_PAYLOAD,
                evaluate(config("{\"enabled\":true}"), gps(), proximity));

        RVP_FuseData groundProximity = gson.fromJson("{\"ground_proximity_fuse_distance\":8}", RVP_FuseData.class);
        assertEquals(RVP_VirtualMissileEligibility.Result.INELIGIBLE_WORLD_DEPENDENT_PAYLOAD,
                evaluate(config("{\"enabled\":true}"), gps(), groundProximity));
    }

    /** 没有离地高度门的 GPS 才能脱离地形查询；PRESET 原本不执行该门。 */
    @Test
    void terrainDependentGuidanceStaysRealUnlessPresetBypassesTheEnvelope() {
        RVP_GuidanceData terrain = gps();
        // 调用生产区间构造器，建立真实目标离地高度门，而非用测试布尔量替代配置。
        setField(terrain, "guidanceAltitudeRange", RVP_Range.closed(0f, 100f));
        assertEquals(RVP_VirtualMissileEligibility.Result.INELIGIBLE_TERRAIN_GUIDANCE,
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

    private static RVP_VirtualMissileEligibility.Result evaluate(
            RVP_VirtualMidcourseData config, RVP_GuidanceData guidance, RVP_FuseData fuse) {
        return RVP_VirtualMissileEligibility.evaluateConfiguration(
                config, guidance, fuse, false, false, false, false, 0, 0);
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
            Field field = RVP_GuidanceData.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
