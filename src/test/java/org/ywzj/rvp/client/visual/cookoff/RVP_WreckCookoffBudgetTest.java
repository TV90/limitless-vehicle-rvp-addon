package org.ywzj.rvp.client.visual.cookoff;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证多残骸公平性、硬上限和距离边界，而非逐行复写实现。 */
class RVP_WreckCookoffBudgetTest {

    @Test
    void tenWrecksShareBudgetInsteadOfFirstVehiclesConsumingEverything() {
        int perVehicle = RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE;
        int total = RVP_WreckCookoffBudget.PARTICLES_PER_TICK;

        // 情形一：额度足够时十车必须均分，每车都拿满自己的请求份额
        int[] requests = new int[10];
        Arrays.fill(requests, 10);
        int[] fair = RVP_WreckCookoffBudget.allocate(requests, 100, perVehicle);
        assertEquals(100, Arrays.stream(fair).sum());
        assertTrue(Arrays.stream(fair).allMatch(value -> value == 10));

        // 情形二：请求远大于全局额度时，逐轮均分——每轮每车各 1 枚，
        // 因此在触到单车上限之前，全局总额才是约束：每车 = 额度 / 车数，而不是前车抢占
        int[] hungry = new int[10];
        Arrays.fill(hungry, 10000);
        int[] shared = RVP_WreckCookoffBudget.allocate(hungry, total, perVehicle);
        assertEquals(total, Arrays.stream(shared).sum(), "发放总量不得超过全局额度");
        assertTrue(Arrays.stream(shared).allMatch(value -> value == total / 10),
                "十车均分时每车额度必须一致，不能前车抢占");
        assertTrue(Arrays.stream(shared).allMatch(value -> value <= perVehicle), "单车不得超过单车上限");
    }

    @Test
    void LargeMultibarrelVehicleCannotStarveSmallVehicle() {
        int perVehicle = RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE;
        int total = RVP_WreckCookoffBudget.PARTICLES_PER_TICK;
        // 调用生产预算器，大车受单车限制，小车请求仍完整满足。
        assertArrayEquals(new int[]{perVehicle, 4},
                RVP_WreckCookoffBudget.allocate(new int[]{10000, 4}, total, perVehicle));
    }

    @Test
    void scarceFinalSlotsPreferNearerVehiclesWithoutExceedingRequests() {
        int perVehicle = RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE;
        // 调用生产预算器；调用方已按距离排序，末轮余额只发给近车。
        assertArrayEquals(new int[]{2, 1, 1}, RVP_WreckCookoffBudget.allocate(new int[]{5, 5, 5}, 4, perVehicle));
        assertArrayEquals(new int[]{0, 0, 1}, RVP_WreckCookoffBudget.allocate(new int[]{0, -1, 1}, 160, perVehicle));
    }

    @Test
    void disabledDensityAndNoVehiclesHaveNoAllocation() {
        int perVehicle = RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE;
        // 调用生产预算器，零总预算或空场景都不产生粒子。
        assertArrayEquals(new int[]{0}, RVP_WreckCookoffBudget.allocate(new int[]{24}, 0, perVehicle));
        assertArrayEquals(new int[0], RVP_WreckCookoffBudget.allocate(new int[0], 160, perVehicle));
    }

    @Test
    void distanceLodOnlyDropsAtDocumentedBoundaries() {
        // 调用生产距离策略，边界处仍保留当前档位，256 格外完全停止。
        double full = RVP_WreckCookoffBudget.FULL_DETAIL_DISTANCE;
        assertEquals(1, RVP_WreckCookoffBudget.distanceScale(full));
        assertEquals(0.6, RVP_WreckCookoffBudget.distanceScale(full + 0.001));
        assertEquals(0.6, RVP_WreckCookoffBudget.distanceScale(256));
        assertEquals(0.3, RVP_WreckCookoffBudget.distanceScale(256.001));
        assertEquals(0.3, RVP_WreckCookoffBudget.distanceScale(512));
        assertEquals(0, RVP_WreckCookoffBudget.distanceScale(512.001));
    }

    @Test
    void perVehicleBudgetCoversEveryOutletOfAFullyArmedVehicle() {
        // 关键不变量：出口越加越多时，单车额度必须仍然覆盖"每个出口至少一枚/tick"，
        // 否则新加的接缝点会被预算静默压掉，"同一时刻大量喷火"名存实亡。
        int baseDemand = RVP_WreckCookoffBudget.baseDemand(List.of(
                RVP_WreckCookoffResolver.Kind.HATCH,
                RVP_WreckCookoffResolver.Kind.SEAM,
                RVP_WreckCookoffResolver.Kind.MUZZLE));
        assertTrue(RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE >= baseDemand,
                "单车上限 " + RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE + " 低于基础需求 " + baseDemand);
    }

    @Test
    void concurrentSeamPointsOccupyDistinctSlotsAroundTheRim() {
        int points = RVP_WreckCookoffResolver.SEAM_POINTS_PER_TURRET;
        // "同一时刻 12 处分散喷火"的硬前提：每个采样点占一个互不重叠的等分槽，
        // 且槽内抖动不超过半个槽宽，因此相邻点不会贴到一起
        for (int point = 0; point < points; point++) {
            double fraction = RVP_WreckCookoffGeometry.seamAzimuthFraction(0, point, points);
            double center = (point + 0.5) / points;
            assertTrue(Math.abs(fraction - center) < 0.5 / points,
                    "采样点 " + point + " 越出了自己的槽位: " + fraction);
        }
        assertTrue(RVP_WreckCookoffGeometry.SEAM_POSITION_JITTER > 0
                        && RVP_WreckCookoffGeometry.SEAM_POSITION_JITTER < 1,
                "槽内抖动上限必须落在 (0,1)，否则会越槽");
    }

    @Test
    void onlySparkKindsCarryWeightAndMuzzleIsRetired() {
        // 舱盖与接缝是仅有的两类火星出口，权重必须为正
        assertTrue(RVP_WreckCookoffBudget.weightOf(RVP_WreckCookoffResolver.Kind.HATCH) > 0);
        assertTrue(RVP_WreckCookoffBudget.weightOf(RVP_WreckCookoffResolver.Kind.SEAM) > 0);
        assertEquals(RVP_WreckCookoffBudget.WEIGHT_HATCH, RVP_WreckCookoffBudget.weightOf(
                RVP_WreckCookoffResolver.Kind.HATCH));
        assertEquals(RVP_WreckCookoffBudget.WEIGHT_SEAM, RVP_WreckCookoffBudget.weightOf(
                RVP_WreckCookoffResolver.Kind.SEAM));
        // 用户定版 2026-10-06：取消炮口喷火星，只保留独立灰烟 —— 炮口权重必须为 0
        assertEquals(0, RVP_WreckCookoffBudget.WEIGHT_MUZZLE);
        assertEquals(0, RVP_WreckCookoffBudget.weightOf(RVP_WreckCookoffResolver.Kind.MUZZLE));
    }

    @Test
    void muzzleDoesNotConsumeSparkBudget() {
        // 炮口权重为 0 时，基础需求只由舱盖与接缝构成，炮口不再申请额度
        int withMuzzle = RVP_WreckCookoffBudget.baseDemand(List.of(
                RVP_WreckCookoffResolver.Kind.HATCH,
                RVP_WreckCookoffResolver.Kind.SEAM,
                RVP_WreckCookoffResolver.Kind.MUZZLE));
        int withoutMuzzle = RVP_WreckCookoffBudget.baseDemand(List.of(
                RVP_WreckCookoffResolver.Kind.HATCH,
                RVP_WreckCookoffResolver.Kind.SEAM));
        assertEquals(withoutMuzzle, withMuzzle, "炮口不应再占用火星预算");
        assertTrue(RVP_WreckCookoffBudget.PARTICLES_PER_VEHICLE >= withoutMuzzle);
    }

    @Test
    void effectBudgetIsSeparateAndBounded() {
        // 车顶火焰与炮口灰烟使用独立额度，不应改变火星的 320/tick 全局预算。
        assertEquals(320, RVP_WreckCookoffBudget.PARTICLES_PER_TICK);
        assertEquals(RVP_WreckCookoffBudget.MAX_COLUMNS * RVP_WreckCookoffBudget.EFFECT_LAYERS_PER_COLUMN,
                RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_TICK);
        assertEquals(48, RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_VEHICLE);
        assertTrue(RVP_WreckCookoffBudget.EFFECT_PARTICLES_PER_TICK > 0);
    }
}
