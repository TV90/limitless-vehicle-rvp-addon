package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证侧翻姿态下的世界竖直喷焰、以及接缝采样点/方向的随机性与约束。
 *
 * <p>接缝部分覆盖"沿接缝随机采样 + 单点持续一小段 + 同炮塔多点同时喷 + 水平 0°～+30°"的要求：
 * 采样必须落在炮塔 OBB 底缘上、法线必须朝外、俯仰必须落在 0°～+30°、
 * 同一 seed 必须可复现、不同 seed 必须换点、同炮塔多点必须绕满整圈不留空档。</p>
 */
class RVP_WreckCookoffGeometryTest {

    /** 炮塔 OBB 的宽与深，取常见主战坦克炮塔量级，单位格。 */
    private static final double TURRET_WIDTH = 2.6;
    private static final double TURRET_DEPTH = 3.4;
    /** 底缘采样点总数，取生产常量，避免测试与实现各写一份。 */
    private static final int POINTS = RVP_WreckCookoffResolver.SEAM_POINTS_PER_TURRET;
    /** 判定"点落在某条边上"的坐标容差，单位格。 */
    private static final double EDGE_EPSILON = 1e-6;

    @Test
    void hatchPositionRollsWithVehicleButFlameRemainsWorldVertical() {
        Quaternionf rolled = new Quaternionf().rotationZ((float) (Math.PI / 2));
        // 调用真实坐标计算；局部顶部翻到侧面，出口方向仍须由解析器给世界向上
        Vec3 point = RVP_WreckCookoffGeometry.point(new Vec3(10, 20, 30), rolled, new Vec3(0, 2, 0));
        assertEquals(8, point.x, 1e-5);
        assertEquals(20, point.y, 1e-5);
        assertEquals(30, point.z, 1e-5);
        assertEquals(new Vec3(0, 1, 0), RVP_WreckCookoffGeometry.UP);
    }

    @Test
    void sharedRotationIsNeverMutatedByMultipleOutlets() {
        Quaternionf rotation = new Quaternionf().rotationXYZ(0.3f, 1.2f, 0.5f);
        Quaternionf snapshot = new Quaternionf(rotation);
        // 调用共享变换两次，不得让后一个出口受到前一次旋转的污染
        RVP_WreckCookoffGeometry.point(Vec3.ZERO, rotation, new Vec3(2, 1, 0));
        RVP_WreckCookoffGeometry.seamDirection(rotation, new Vec3(1, 0, 0), 7, 3);
        assertEquals(snapshot, rotation);
    }

    @Test
    void seamSampleLiesOnTheTurretRimWithOutwardNormal() {
        double halfWidth = TURRET_WIDTH * 0.49;
        double halfDepth = TURRET_DEPTH * 0.49;
        for (int seed = 0; seed < 60; seed++) {
            for (int point = 0; point < POINTS; point++) {
                RVP_WreckCookoffGeometry.SeamSample sample =
                        RVP_WreckCookoffGeometry.seamSample(TURRET_WIDTH, TURRET_DEPTH, seed, point, POINTS);
                // 采样点必须落在矩形轮廓上：至少一个轴顶到半尺寸，且两个轴都不越界
                assertTrue(Math.abs(Math.abs(sample.x()) - halfWidth) < EDGE_EPSILON
                                || Math.abs(Math.abs(sample.z()) - halfDepth) < EDGE_EPSILON,
                        "seed=" + seed + " point=" + point + " 的采样点不在炮塔底缘上");
                assertTrue(Math.abs(sample.x()) <= halfWidth + EDGE_EPSILON
                                && Math.abs(sample.z()) <= halfDepth + EDGE_EPSILON,
                        "seed=" + seed + " point=" + point + " 的采样点越出 OBB");
                // 法线必须是单位向量、水平、且朝外
                assertEquals(1.0, sample.normal().length(), 1e-9);
                assertEquals(0.0, sample.normal().y, 1e-9);
                assertTrue(sample.normal().dot(new Vec3(sample.x(), 0, sample.z())) > 0,
                        "seed=" + seed + " point=" + point + " 的接缝法线朝内");
            }
        }
    }

    @Test
    void seamSampleIsReproducibleAndActuallyVariesAcrossSeeds() {
        RVP_WreckCookoffGeometry.SeamSample first =
                RVP_WreckCookoffGeometry.seamSample(TURRET_WIDTH, TURRET_DEPTH, 42, 1, POINTS);
        RVP_WreckCookoffGeometry.SeamSample again =
                RVP_WreckCookoffGeometry.seamSample(TURRET_WIDTH, TURRET_DEPTH, 42, 1, POINTS);
        assertEquals(first.x(), again.x(), 1e-12);
        assertEquals(first.z(), again.z(), 1e-12);
        // 换到另一段后同一采样点必须换位置，否则不会有"换点"观感
        RVP_WreckCookoffGeometry.SeamSample next =
                RVP_WreckCookoffGeometry.seamSample(TURRET_WIDTH, TURRET_DEPTH, 43, 1, POINTS);
        double moved = Math.hypot(first.x() - next.x(), first.z() - next.z());
        assertTrue(moved > 0.02, "换点后位置几乎没变，位移仅 " + moved);
    }

    @Test
    void seamPointsAreEvenlySpacedInAzimuthAroundTheTurret() {
        // 回归（用户实测"喷口集中在左后/左前/右前"）：
        // 旧实现按"底缘弧长"等分，而矩形四条边从中心张开的角差别很大——宽而浅的炮塔
        // 前/后边张角远大于侧边，于是采样点在短边一侧挤成一团。必须按方位等分。
        // 这里逐个种子检查：12 个点的方位必须等间隔，相邻方位差恒 ≈ 1/12 圈。
        double expected = 1.0 / POINTS;
        for (int seed = 0; seed < 200; seed++) {
            double[] azimuths = new double[POINTS];
            for (int point = 0; point < POINTS; point++) {
                RVP_WreckCookoffGeometry.SeamSample sample =
                        RVP_WreckCookoffGeometry.seamSample(TURRET_WIDTH, TURRET_DEPTH, seed, point, POINTS);
                azimuths[point] = RVP_WreckCookoffGeometry.azimuthOf(sample.x(), sample.z());
            }
            Arrays.sort(azimuths);
            for (int i = 0; i < azimuths.length; i++) {
                double next = i + 1 < azimuths.length ? azimuths[i + 1] : azimuths[0] + 1.0;
                double gap = next - azimuths[i];
                // 抖动上限为半个槽 → 方位间距 ∈ [0.5, 1.5] 个槽
                assertTrue(gap >= expected * 0.5 - 1e-9 && gap <= expected * 1.5 + 1e-9,
                        "seed=" + seed + " 的方位间距 " + gap + " 越界（期望 " + expected + "）");
            }
        }
    }

    @Test
    void seamSampleStaysOnTheRimForBothWideAndDeepTurrets() {
        // 宽而浅 / 窄而深都要落在矩形轮廓上，且方位均匀——旧弧长方案在两种极端下偏差最大
        double[][] boxes = {{3.2, 1.1}, {1.1, 3.2}, {2.6, 3.4}};
        for (double[] box : boxes) {
            double halfWidth = box[0] * 0.49;
            double halfDepth = box[1] * 0.49;
            double[] azimuths = new double[POINTS];
            for (int point = 0; point < POINTS; point++) {
                RVP_WreckCookoffGeometry.SeamSample sample =
                        RVP_WreckCookoffGeometry.seamSample(box[0], box[1], 7, point, POINTS);
                assertTrue(Math.abs(Math.abs(sample.x()) - halfWidth) < EDGE_EPSILON
                                || Math.abs(Math.abs(sample.z()) - halfDepth) < EDGE_EPSILON,
                        "box=" + Arrays.toString(box) + " point=" + point + " 的采样点不在底缘上");
                assertTrue(sample.normal().dot(new Vec3(sample.x(), 0, sample.z())) > 0,
                        "box=" + Arrays.toString(box) + " point=" + point + " 的法线朝内");
                azimuths[point] = RVP_WreckCookoffGeometry.azimuthOf(sample.x(), sample.z());
            }
            Arrays.sort(azimuths);
            double expected = 1.0 / POINTS;
            for (int i = 0; i < azimuths.length; i++) {
                double next = i + 1 < azimuths.length ? azimuths[i + 1] : azimuths[0] + 1.0;
                double gap = next - azimuths[i];
                assertTrue(gap >= expected * 0.5 - 1e-9 && gap <= expected * 1.5 + 1e-9,
                        "box=" + Arrays.toString(box) + " 的方位间距越界: " + gap);
            }
        }
    }

    @Test
    void seamPointOrderNeverSwapsAcrossSeeds() {
        // 槽内抖动被限制在半槽内，因此各点沿底缘的顺序恒定——这是"间距有下界"的前提；
        // 若顺序会互换，就意味着两个点可能贴到一起
        for (int seed = 0; seed < 200; seed++) {
            double previous = -1.0;
            for (int point = 0; point < POINTS; point++) {
                double fraction = RVP_WreckCookoffGeometry.seamAzimuthFraction(seed, point, POINTS);
                assertTrue(fraction > previous,
                        "seed=" + seed + " 下第 " + point + " 点的槽位顺序发生互换");
                previous = fraction;
            }
            assertTrue(previous < 1.0, "seed=" + seed + " 的归一化位置越界: " + previous);
        }
    }

    @Test
    void seamSlotJitterCoversTheWholeRimAcrossSeeds() {
        // 槽位固定、只有槽内抖动时，抖动质量差会让点位长期偏向槽位一侧。
        // 逐个槽位检查：该槽内出现过的位置必须既靠近槽首也靠近槽尾（抖动居中，满量程时覆盖整个槽）
        double lowBound = 0.5 - RVP_WreckCookoffGeometry.SEAM_POSITION_JITTER * 0.5;
        double highBound = 0.5 + RVP_WreckCookoffGeometry.SEAM_POSITION_JITTER * 0.5;
        for (int point = 0; point < POINTS; point++) {
            double lowest = 1.0;
            double highest = 0.0;
            for (int seed = 0; seed < 200; seed++) {
                double fraction = RVP_WreckCookoffGeometry.seamAzimuthFraction(seed, point, POINTS);
                double withinSlot = fraction * POINTS - point;
                lowest = Math.min(lowest, withinSlot);
                highest = Math.max(highest, withinSlot);
            }
            assertTrue(lowest < lowBound + 0.05,
                    "第 " + point + " 个槽位从未取到靠前位置: " + lowest);
            assertTrue(highest > highBound - 0.05,
                    "第 " + point + " 个槽位从未取到靠后位置: " + highest);
        }
    }

    @Test
    void seamJitterSpansTheWholeUnitInterval() {
        // 回归：旧 seedRandom（乘黄金比例取小数）在 seed*13+point*7 上只散出 0～0.4 的窄带。
        // 混合器必须把输出铺满 [0,1)，否则槽内位置会长期偏一侧。
        double lowest = 1.0;
        double highest = 0.0;
        for (int seed = 0; seed < 200; seed++) {
            for (int point = 0; point < POINTS; point++) {
                double jitter = RVP_WreckCookoffGeometry.seedRandom(seed * 13 + point * 7);
                lowest = Math.min(lowest, jitter);
                highest = Math.max(highest, jitter);
            }
        }
        assertTrue(lowest < 0.05, "抖动没有覆盖起点附近: " + lowest);
        assertTrue(highest > 0.95, "抖动没有覆盖末端附近: " + highest);
    }

    @Test
    void seamSamplingFillsEveryArcOfTheRim() {
        // 回归：用户实测"喷口集中在左后/左前/右前"；采样必须绕满整圈，不留成片空区。
        // 把周长按生产槽位分成 POINTS 格并逐槽检查：每个槽在若干 seed 内都必须被踩到
        // （槽位固定，靠槽内抖动覆盖，因此逐槽统计即可）。
        int buckets = POINTS * 2;
        int[] bucketHits = new int[buckets];
        for (int seed = 0; seed < 300; seed++) {
            for (int point = 0; point < POINTS; point++) {
                double fraction = RVP_WreckCookoffGeometry.seamAzimuthFraction(seed, point, POINTS);
                int bucket = (int) Math.floor(fraction * buckets);
                bucketHits[Math.min(buckets - 1, Math.max(0, bucket))]++;
            }
        }
        int empty = 0;
        for (int hits : bucketHits) {
            if (hits == 0) {
                empty++;
            }
        }
        assertEquals(0, empty, "有 " + empty + "/" + buckets + " 个底缘小区间从未被采样到，喷口仍会集中");
    }

    @Test
    void seamSampleCoversAllFourRimEdges() {
        boolean[] edgeSeen = new boolean[4];
        for (int seed = 0; seed < 400; seed++) {
            for (int point = 0; point < POINTS; point++) {
                RVP_WreckCookoffGeometry.SeamSample sample =
                        RVP_WreckCookoffGeometry.seamSample(TURRET_WIDTH, TURRET_DEPTH, seed, point, POINTS);
                if (Math.abs(sample.z() + TURRET_DEPTH * 0.49) < EDGE_EPSILON) edgeSeen[0] = true;
                if (Math.abs(sample.x() - TURRET_WIDTH * 0.49) < EDGE_EPSILON) edgeSeen[1] = true;
                if (Math.abs(sample.z() - TURRET_DEPTH * 0.49) < EDGE_EPSILON) edgeSeen[2] = true;
                if (Math.abs(sample.x() + TURRET_WIDTH * 0.49) < EDGE_EPSILON) edgeSeen[3] = true;
            }
        }
        for (int edge = 0; edge < edgeSeen.length; edge++) {
            assertTrue(edgeSeen[edge], "第 " + edge + " 条底缘没有被采样覆盖到");
        }
    }

    @Test
    void hatchColumnStaysWorldUpWhileOnlySparksUseTheCone() {
        // 用户定版：舱盖顶部火柱固定向上喷（保持原行为），只有火星喷口呈 30° 锥形散开。
        // 回归点：火星方向现在是随机锥向，若火柱复用它，柱子会跟着歪。
        for (int seed = 0; seed < 200; seed++) {
            Vec3 sparkAxis = RVP_WreckCookoffGeometry.hatchConeDirection(seed);
            Vec3 column = RVP_WreckCookoffGeometry.columnDirection(true, sparkAxis);
            assertEquals(RVP_WreckCookoffGeometry.UP, column,
                    "seed=" + seed + " 的舱盖柱偏离了世界向上");
            // 同时确认火星方向确实是锥内的（两者已解耦，互不影响）
            assertTrue(sparkAxis.dot(RVP_WreckCookoffGeometry.UP)
                            >= Math.cos(Math.toRadians(RVP_WreckCookoffGeometry.HATCH_CONE_HALF_ANGLE_DEGREES)) - 1e-9);
        }
        // 非舱盖出口（如炮口）仍按自己的火星/炮管方向，不被舱盖规则波及
        Vec3 arbitrary = new Vec3(0.3, 0.5, -0.8).normalize();
        assertEquals(arbitrary, RVP_WreckCookoffGeometry.columnDirection(false, arbitrary));
    }

    @Test
    void directionInterpolationSurvivesDegenerateInput() {
        Vec3 up = RVP_WreckCookoffGeometry.UP;
        Vec3 forward = new Vec3(0.0, 0.0, -1.0);
        assertEquals(up, RVP_WreckCookoffGeometry.interpolateDirection(up, up, 0.5f));
        // 前后相反方向在中点退化为零向量，必须回退当前帧而不是返回 NaN
        Vec3 degenerate = RVP_WreckCookoffGeometry.interpolateDirection(forward, forward.scale(-1), 0.5f);
        assertEquals(forward.scale(-1), degenerate);
        assertEquals(1.0, degenerate.length(), 1e-9);
    }

    @Test
    void hatchConeDirectionStaysWithinThirtyDegreesOfWorldUp() {
        // 用户定版：舱盖火星沿世界 Y 轴向上做 30° 圆锥随机采样
        double cosLimit = Math.cos(Math.toRadians(RVP_WreckCookoffGeometry.HATCH_CONE_HALF_ANGLE_DEGREES));
        double lowestCos = 1.0;
        double highestCos = -1.0;
        for (int seed = 0; seed < 300; seed++) {
            Vec3 direction = RVP_WreckCookoffGeometry.hatchConeDirection(seed);
            assertEquals(1.0, direction.length(), 1e-9);
            double cos = direction.dot(RVP_WreckCookoffGeometry.UP);
            // 与世界上方夹角必须 ≤ 30°，即 cos ≥ cos30°；也就绝不会朝下
            assertTrue(cos >= cosLimit - 1e-9,
                    "seed=" + seed + " 的舱盖火星偏离世界 Y 轴超过 30°: cos=" + cos);
            lowestCos = Math.min(lowestCos, cos);
            highestCos = Math.max(highestCos, cos);
        }
        // 必须真的取到锥轴附近与锥壁附近，否则等于没做圆锥随机
        assertTrue(highestCos > 0.99, "从未取到接近正上方的方向: " + highestCos);
        assertTrue(lowestCos < cosLimit + 0.05, "从未取到接近 30° 锥壁的方向: " + lowestCos);
    }

    @Test
    void hatchConeDirectionSpreadsEvenlyAroundTheAxis() {
        // 圆锥内不能偏向某个方位：按方位角分箱统计，各箱计数应大致相等
        int bins = 8;
        int[] hits = new int[bins];
        int samples = 0;
        for (int seed = 0; seed < 400; seed++) {
            Vec3 direction = RVP_WreckCookoffGeometry.hatchConeDirection(seed);
            double azimuth = Math.atan2(direction.z, direction.x);
            int bin = (int) Math.floor((azimuth + Math.PI) / (2.0 * Math.PI) * bins) % bins;
            hits[Math.min(bins - 1, Math.max(0, bin))]++;
            samples++;
        }
        // 每箱理想 100 个样本；允许 ±50% 偏差，但任何一箱都不能为空或独占
        for (int bin = 0; bin < bins; bin++) {
            assertTrue(hits[bin] > samples / bins / 2 && hits[bin] < samples / bins * 2,
                    "方位箱 " + bin + " 分布异常: " + hits[bin] + "/" + samples);
        }
    }

    @Test
    void hatchConeDirectionsAreReproducibleAndVaryAcrossSeeds() {
        Vec3 first = RVP_WreckCookoffGeometry.hatchConeDirection(42);
        Vec3 again = RVP_WreckCookoffGeometry.hatchConeDirection(42);
        assertEquals(first.x, again.x, 1e-12);
        assertEquals(first.y, again.y, 1e-12);
        assertEquals(first.z, again.z, 1e-12);
        // 换点后方向必须明显不同，否则看不出"换向"
        Vec3 next = RVP_WreckCookoffGeometry.hatchConeDirection(43);
        assertTrue(first.distanceTo(next) > 0.02, "换点后方向几乎没变: " + first.distanceTo(next));
    }

    @Test
    void seamDirectionPitchStaysWithinZeroToThirtyDegrees() {
        Quaternionf yawed = new Quaternionf().rotationY(0.7f);
        double sinMin = 0.0;
        double sinMax = Math.sin(Math.toRadians(RVP_WreckCookoffGeometry.SEAM_ELEVATION_DEGREES));
        double lowest = 1.0;
        double highest = 0.0;
        for (int seed = 0; seed < 200; seed++) {
            for (int point = 0; point < POINTS; point++) {
                Vec3 direction = RVP_WreckCookoffGeometry.seamDirection(yawed, new Vec3(1, 0, 0), seed, point);
                assertEquals(1.0, direction.length(), 1e-9);
                // "水平 0°~+30° 随机"：竖直分量必须落在 [sin0°, sin30°]，即永不向下、也不超过 30°
                assertTrue(direction.y >= sinMin - 1e-6 && direction.y <= sinMax + 1e-6,
                        "seed=" + seed + " point=" + point + " 的接缝俯仰越界: " + direction.y);
                lowest = Math.min(lowest, direction.y);
                highest = Math.max(highest, direction.y);
            }
        }
        // 还必须真的取到区间两端附近的样，否则等于没随机（退化成固定角）
        assertTrue(lowest < sinMin + 0.05, "俯仰没有取到接近水平的样: " + lowest);
        assertTrue(highest > sinMax - 0.05, "俯仰没有取到接近 +30° 的样: " + highest);
    }

    @Test
    void seamDirectionKeepsOutwardAzimuthWithOnlySmallJitter() {
        Vec3 normal = new Vec3(0, 0, -1);
        double baseYaw = RVP_WreckCookoffGeometry.seamYaw(new Quaternionf(), normal);
        for (int seed = 0; seed < 200; seed++) {
            for (int point = 0; point < POINTS; point++) {
                Vec3 direction = RVP_WreckCookoffGeometry.seamDirection(new Quaternionf(), normal, seed, point);
                Vec3 horizontal = new Vec3(direction.x, 0, direction.z).normalize();
                double yaw = org.ywzj.vehicle.util.VectorUtil.vecToRot(horizontal).y;
                // 水平朝向以采样点外法线为中心，只允许 ±抖动上限的偏摆
                double delta = Math.abs(Mth.wrapDegrees((float) (yaw - baseYaw)));
                assertTrue(delta <= RVP_WreckCookoffGeometry.SEAM_YAW_JITTER_DEGREES + 1e-6,
                        "seed=" + seed + " point=" + point + " 的接缝偏摆超限: " + delta);
            }
        }
    }

    @Test
    void sampleBurstTicksAlwaysStayInConfiguredRange() {
        for (int seed = 0; seed < 500; seed++) {
            int burst = RVP_WreckCookoffGeometry.sampleBurstTicks(seed);
            assertTrue(burst >= RVP_WreckCookoffGeometry.SAMPLE_BURST_MIN_TICKS
                            && burst <= RVP_WreckCookoffGeometry.SAMPLE_BURST_MAX_TICKS,
                    "seed=" + seed + " 的接缝持续时长越界: " + burst);
        }
        // 区间两端都必须真的取到，否则等于写死一个值
        boolean sawMin = false;
        boolean sawMax = false;
        for (int seed = 0; seed < 500; seed++) {
            int burst = RVP_WreckCookoffGeometry.sampleBurstTicks(seed);
            sawMin |= burst == RVP_WreckCookoffGeometry.SAMPLE_BURST_MIN_TICKS;
            sawMax |= burst == RVP_WreckCookoffGeometry.SAMPLE_BURST_MAX_TICKS;
        }
        assertTrue(sawMin && sawMax, "持续时长没有取到区间两端");
    }

    /** 复刻 {@code RVP_WreckCookoffResolver.samplingSeed}：以整周期除法 + 周期内查表求段号。 */
    private static int seamSegment(long tick, int sampleIndex) {
        int cycle = RVP_WreckCookoffResolver.SEAM_CYCLE_TICKS;
        int cycleTicks = RVP_WreckCookoffResolver.seamCycleTicks();
        int step = (int) Math.floorMod(tick + sampleIndex * 3L, 1L << 30);
        int segment = Math.floorDiv(step, cycleTicks) * cycle;
        int remaining = Math.floorMod(step, cycleTicks);
        int indexInCycle = 0;
        while (indexInCycle < cycle && remaining >= RVP_WreckCookoffGeometry.sampleBurstTicks(indexInCycle)) {
            remaining -= RVP_WreckCookoffGeometry.sampleBurstTicks(indexInCycle);
            segment++;
            indexInCycle++;
        }
        return segment;
    }

    @Test
    void samplingSeedHoldsEachPointForItsBurstThenMoves() {
        int min = RVP_WreckCookoffGeometry.SAMPLE_BURST_MIN_TICKS;
        int max = RVP_WreckCookoffGeometry.SAMPLE_BURST_MAX_TICKS;
        int previousSeed = Integer.MIN_VALUE;
        int segmentLength = 0;
        long totalTicks = 0;
        long totalSegments = 0;
        for (long tick = 0; tick < 5000; tick++) {
            int segment = seamSegment(tick, 0);
            assertTrue(segment >= 0, "tick=" + tick + " 的采样种子为负: " + segment);
            if (segment != previousSeed) {
                if (previousSeed != Integer.MIN_VALUE) {
                    assertTrue(segmentLength >= min && segmentLength <= max,
                            "采样段时长越界: " + segmentLength);
                    totalTicks += segmentLength;
                    totalSegments++;
                }
                previousSeed = segment;
                segmentLength = 1;
            } else {
                segmentLength++;
            }
        }
        double average = totalSegments == 0 ? 0 : (double) totalTicks / totalSegments;
        assertTrue(average >= min && average <= max, "平均采样段长越界: " + average);
    }

    @Test
    void concurrentSeamPointsDoNotReseedOnTheSameTick() {
        // 各点相位错开：同 tick 下相邻采样点的段号不应总是一起变化
        int simultaneousChanges = 0;
        int lastFirst = seamSegment(0, 0);
        int lastSecond = seamSegment(0, 1);
        for (long tick = 1; tick < 2000; tick++) {
            int first = seamSegment(tick, 0);
            int second = seamSegment(tick, 1);
            if (first != lastFirst && second != lastSecond) {
                simultaneousChanges++;
            }
            lastFirst = first;
            lastSecond = second;
        }
        assertTrue(simultaneousChanges < 300,
                "相邻采样点换点时刻几乎同步，同步次数=" + simultaneousChanges);
    }

    @Test
    void samplingSeedStaysNonNegativeNearTheIntegerOverflowBoundary() {
        // 回归：旧实现用 (int) Math.floorMod(tick, Integer.MAX_VALUE)，世界运行到 2.1e9 tick 后会溢出成负种子
        for (long tick : new long[]{2_100_000_000L, 2_147_483_647L, 4_294_967_296L, Integer.MAX_VALUE + 1L}) {
            for (int point = 0; point < POINTS; point++) {
                int segment = seamSegment(tick, point);
                assertTrue(segment >= 0, "tick=" + tick + " point=" + point + " 的采样种子为负: " + segment);
                assertTrue(RVP_WreckCookoffGeometry.seedRandom(segment) >= 0.0
                                && RVP_WreckCookoffGeometry.seedRandom(segment) < 1.0,
                        "tick=" + tick + " 的种子散列越界");
            }
        }
    }

    @Test
    void seedRandomStaysInUnitIntervalForNegativeAndLargeSeeds() {
        for (int seed : new int[]{0, 1, 7, 12345, Integer.MAX_VALUE, -1, -98765, Integer.MIN_VALUE}) {
            double value = RVP_WreckCookoffGeometry.seedRandom(seed);
            assertTrue(value >= 0.0 && value < 1.0, "seed=" + seed + " 的散列值越界: " + value);
        }
    }

    /** 采样点沿底缘周长（自 -Z 前边左端起算）的位置，单位格；四边分别按各自参数化还原。 */
    private static double rimPosition(RVP_WreckCookoffGeometry.SeamSample sample,
                                      double halfWidth, double halfDepth) {
        if (Math.abs(sample.z() + halfDepth) < EDGE_EPSILON) {
            // 前边（-Z）
            return sample.x() + halfWidth;
        }
        if (Math.abs(sample.x() - halfWidth) < EDGE_EPSILON) {
            // 右边（+X）
            return halfWidth + (sample.z() + halfDepth);
        }
        if (Math.abs(sample.z() - halfDepth) < EDGE_EPSILON) {
            // 后边（+Z）
            return halfWidth * 2.0 + halfDepth - sample.x();
        }
        // 左边（-X）
        return halfWidth * 3.0 + halfDepth * 2.0 - sample.z();
    }
}
