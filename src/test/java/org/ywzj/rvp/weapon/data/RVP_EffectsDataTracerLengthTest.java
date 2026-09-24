package org.ywzj.rvp.weapon.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 机枪曳光长度公式单测：长度 = min(0.3 × 弹速 × effects_data.tracer_length_scale, 已飞距离 × 0.8)。
 * 默认倍率 1.0 必须与改造前行为逐位一致。
 */
class RVP_EffectsDataTracerLengthTest {

    private static final double DELTA = 1.0E-6;

    @Test
    void defaultScaleKeepsCurrentLength() {
        assertEquals(4.8, RVP_EffectsData.resolveTracerLength(16, 100, 1f), DELTA);
    }

    @Test
    void doubledScaleDoublesLength() {
        assertEquals(9.6, RVP_EffectsData.resolveTracerLength(16, 100, 2f), DELTA);
    }

    @Test
    void travelDistanceCapsLength() {
        assertEquals(4.0, RVP_EffectsData.resolveTracerLength(16, 5, 2f), DELTA);
    }

    @Test
    void zeroScaleMeansNoTracer() {
        assertEquals(0.0, RVP_EffectsData.resolveTracerLength(16, 100, 0f), DELTA);
    }
}
