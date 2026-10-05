package org.ywzj.rvp.client.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抗荷补偿数学的单元测试（默认耐受倍率 M=1：体力边界 [0, 160]，本体每 tick 恢复量 1，
 * 每 G 消耗 0.8）。判据：稳态不黑视平衡点 = 1 + 1/(0.8×(1−返还率))。
 */
class RVP_AntiGSuitLogicTest {

    @Test
    void zeroLevelReturnsStaminaUnchanged() {
        // 未附魔（0 级）必须原样返回——裸装行为与本体完全一致
        assertEquals(42.5F, RVP_AntiGSuitLogic.compensate(42.5F, 5.0F, 0, 1.0F), 1.0E-6F);
        assertEquals(42.5F, RVP_AntiGSuitLogic.compensate(42.5F, 5.0F, -1, 1.0F), 1.0E-6F);
    }

    @Test
    void ratesMatchApprovedCurve() {
        assertEquals(0.0F, RVP_AntiGSuitLogic.rateForLevel(0), 1.0E-6F);
        assertEquals(0.20F, RVP_AntiGSuitLogic.rateForLevel(1), 1.0E-6F);
        assertEquals(0.35F, RVP_AntiGSuitLogic.rateForLevel(2), 1.0E-6F);
        assertEquals(0.50F, RVP_AntiGSuitLogic.rateForLevel(3), 1.0E-6F);
        // 超界等级取最高档（附魔 maxLevel=3，防御式兜底）
        assertEquals(0.50F, RVP_AntiGSuitLogic.rateForLevel(9), 1.0E-6F);
    }

    @Test
    void positiveGRefundSlowsDrainSymmetrically() {
        // 5G：本体每 tick 消耗 (5-1)×0.8 = 3.2；III 级返还 50% = 1.6
        assertEquals(98.4F + 1.6F, RVP_AntiGSuitLogic.compensate(98.4F, 5.0F, 3, 1.0F), 1.0E-4F);
        // II 级返还 35% = 1.12
        assertEquals(98.4F + 1.12F, RVP_AntiGSuitLogic.compensate(98.4F, 5.0F, 2, 1.0F), 1.0E-4F);
        // 平飞 1G：消耗为 0，返还也为 0
        assertEquals(100.0F, RVP_AntiGSuitLogic.compensate(100.0F, 1.0F, 3, 1.0F), 1.0E-6F);
    }

    @Test
    void negativeGRefundSlowsRedout() {
        // -1G：本体每 tick 上飘 (1-(-1))×0.8 = 1.6；III 级返还 -0.8 → 上飘净 0.8，红视延后一倍
        assertEquals(150.0F - 0.8F, RVP_AntiGSuitLogic.compensate(150.0F, -1.0F, 3, 1.0F), 1.0E-4F);
    }

    @Test
    void neverExceedsBaseStaminaLimits() {
        // 触底/触顶后补偿只能向界内回拉，不得越出本体边界 [100-100M, 100+60M]（M=1 → [0, 160]）
        float atFloor = RVP_AntiGSuitLogic.compensate(0.0F, 9.0F, 3, 1.0F);
        assertTrue(atFloor >= 0.0F && atFloor <= 160.0F, "触底补偿后必须在界内");
        assertTrue(atFloor > 0.0F, "触底后正 G 返还应把体力拉回界内");
        float atCeiling = RVP_AntiGSuitLogic.compensate(160.0F, -3.0F, 3, 1.0F);
        assertTrue(atCeiling >= 0.0F && atCeiling <= 160.0F, "触顶补偿后必须在界内");
        assertTrue(atCeiling < 160.0F, "触顶后负 G 返还应把体力拉回界内");
        // M=2 时边界变宽为 [-100, 220]，补偿仍不得越界
        float wideFloor = RVP_AntiGSuitLogic.compensate(-100.0F, 9.0F, 3, 2.0F);
        assertTrue(wideFloor >= -100.0F && wideFloor <= 220.0F, "M=2 时触底补偿后仍必须在界内");
    }

    @Test
    void steadyStateThresholdMatchesDerivedValues() {
        // 模拟稳态持续 G：体力每 tick 先本体恢复 1、再扣消耗、再补偿，验证平衡点与设计一致
        // III 级（返还 50%）平衡点 = 1 + 1/(0.8×0.5) = 3.5G：3.4G 不坠、3.6G 缓慢下坠
        assertTrue(steadyFalls(3.6F, 3), "3.6G 持续拉杆在 III 级抗荷下应缓慢坠向黑视");
        assertFalse(steadyFalls(3.4F, 3), "3.4G 持续拉杆在 III 级抗荷下应稳定不坠");
        // 裸装平衡点 2.25G：2.5G 应坠
        assertTrue(steadyFalls(2.5F, 0), "2.5G 持续拉杆裸装应缓慢坠向黑视");
    }

    /** 模拟 200 tick 稳态 G（本体恢复 1 → 本体扣 (G-1)×0.8 钳制 → 抗荷返还钳制），返回体力是否净下降。 */
    private boolean steadyFalls(float g, int level) {
        float stamina = 100.0F;
        for (int i = 0; i < 200; i++) {
            if (stamina < 100.0F) {
                stamina += 1.0F;
            } else if (stamina > 100.0F) {
                stamina -= 1.0F;
            }
            stamina = Math.max(0.0F, Math.min(160.0F, stamina - (g - 1.0F) * RVP_AntiGSuitLogic.DRAIN_PER_G));
            stamina = RVP_AntiGSuitLogic.compensate(stamina, g, level, 1.0F);
        }
        return stamina < 99.0F;
    }
}
