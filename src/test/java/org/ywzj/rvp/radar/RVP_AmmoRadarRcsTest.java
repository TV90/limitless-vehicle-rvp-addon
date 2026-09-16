package org.ywzj.rvp.radar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹药分角度雷达信号因子插值测试（2026-09-17）：
 * 迎头（弹头指向观察者）=front、正侧 135°=side、尾向 180°=rear、零速度回退侧向。
 */
class RVP_AmmoRadarRcsTest {

    private static final float FRONT = 0.2f;
    private static final float SIDE = 1.0f;
    private static final float REAR = 0.6f;

    /** 弹体在原点沿 +X 飞行，观察者放在 direction*dist 上。 */
    private static float factorAt(double dirX, double dirY, double dirZ,
                                  double obsX, double obsY, double obsZ) {
        return RVP_AmmoRadarRcs.factorTowards(dirX, dirY, dirZ,
                0, 0, 0, obsX, obsY, obsZ, FRONT, SIDE, REAR);
    }

    @Test
    void headOnReturnsFront() {
        // 观察者在正前方（弹头指向它）→ 迎头因子
        assertEquals(FRONT, factorAt(1, 0, 0, 100, 0, 0), 1.0E-4f);
        // 三维方向也成立（俯冲指向正下方的观察者）
        assertEquals(FRONT, factorAt(0, -1, 0, 0, -100, 0), 1.0E-4f);
    }

    @Test
    void rearReturnsRear() {
        // 观察者在正后方（弹尾对它）→ 尾向因子
        assertEquals(REAR, factorAt(1, 0, 0, -100, 0, 0), 1.0E-4f);
    }

    @Test
    void rearSegmentMidpointInterpolatesFromSideToRear() {
        // 135°（尾向段中点）= side + (rear - side) × sin³(45°)
        double obsX = 100.0D * Math.cos(Math.toRadians(135.0D));
        double obsZ = 100.0D * Math.sin(Math.toRadians(135.0D));
        float factor = factorAt(1, 0, 0, obsX, 0, obsZ);
        double eased = Math.pow(Math.sin(Math.toRadians(45.0D)), 3.0D);
        assertEquals(SIDE + (REAR - SIDE) * (float) eased, factor, 1.0E-4f);
    }

    @Test
    void near90ApproachesSideFromBothSegments() {
        // 90° 附近两侧都趋近侧向因子（分段连续；与战机实现同款 ≤90 分支，
        // 注意 toDegrees(acos(0)) 有 90.00000000000001 浮点伪影，不宜断言恰好 90 的分支归属）
        float justBefore = factorAt(1, 0, 0, 0.1D, 0, 100);
        float justPast = factorAt(1, 0, 0, -0.1D, 0, 100);
        assertTrue(Math.abs(justBefore - SIDE) < 0.01f, "89.9° 应贴近侧向因子: " + justBefore);
        assertTrue(Math.abs(justPast - SIDE) < 0.01f, "90.1° 应贴近侧向因子: " + justPast);
    }

    @Test
    void frontSegmentInterpolatesMonotonically() {
        // 迎头段（0→75°，避开 90° 分支切换点）单调递增且介于 front 与 side 之间
        double previous = -1.0D;
        for (int deg = 0; deg <= 75; deg += 15) {
            float factor = factorAt(1, 0, 0,
                    100.0D * Math.cos(Math.toRadians(deg)), 0,
                    100.0D * Math.sin(Math.toRadians(deg)));
            assertTrue(factor >= FRONT - 1.0E-4f && factor <= SIDE + 1.0E-4f,
                    "因子超出 [front, side] 区间: " + factor);
            assertTrue(factor >= previous - 1.0E-4f, "迎头段因子应单调不减");
            previous = factor;
        }
    }

    @Test
    void rearSegmentDecreasesMonotonicallyTowardRear() {
        // 尾向段（105°→180°）自侧向单调趋于尾向因子（180° 恰为 rear）
        double previous = SIDE + 1.0E-4f;
        for (int deg = 105; deg <= 180; deg += 15) {
            float factor = factorAt(1, 0, 0,
                    100.0D * Math.cos(Math.toRadians(deg)), 0,
                    100.0D * Math.sin(Math.toRadians(deg)));
            assertTrue(factor <= previous + 1.0E-4f, "尾向段因子应单调不增: " + factor);
            previous = factor;
        }
        assertEquals(REAR, previous, 1.0E-4f);
    }

    @Test
    void zeroVelocityFallsBackToSide() {
        assertEquals(SIDE, factorAt(0, 0, 0, 100, 0, 0), 1.0E-4f);
    }

    @Test
    void observerAtSamePositionFallsBackToSide() {
        assertEquals(SIDE, factorAt(1, 0, 0, 0, 0, 0), 1.0E-4f);
    }

    @Test
    void uniformConfigurationAlwaysOne() {
        // 未配置等效 [1,1,1]：任何角度恒 1.0（现有未配置武器零破坏）
        for (int deg = 0; deg <= 180; deg += 30) {
            float factor = RVP_AmmoRadarRcs.factorTowards(1, 0, 0,
                    0, 0, 0,
                    100.0D * Math.cos(Math.toRadians(deg)), 0,
                    100.0D * Math.sin(Math.toRadians(deg)), 1.0f, 1.0f, 1.0f);
            assertEquals(1.0f, factor, 1.0E-4f);
        }
    }
}
