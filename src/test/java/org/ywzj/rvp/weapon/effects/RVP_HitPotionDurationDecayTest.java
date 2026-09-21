package org.ywzj.rvp.weapon.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爆炸药水时长距离衰减纯函数测试（2026-09-22）：爆心满时长 → 杀伤半径边缘衰减到 30%，
 * 线性；缩放后不足 20t 归 0。
 */
class RVP_HitPotionDurationDecayTest {

    @Test
    void 爆心满时长() {
        assertEquals(300, RVP_HitPotionEffectService.scaledDuration(300, 0.0, 8.0f));
    }

    @Test
    void 半径边缘衰减到三成() {
        assertEquals(90, RVP_HitPotionEffectService.scaledDuration(300, 8.0, 8.0f));
    }

    @Test
    void 超出半径钳为边缘值() {
        assertEquals(90, RVP_HitPotionEffectService.scaledDuration(300, 12.0, 8.0f));
    }

    @Test
    void 半径中点约六成五() {
        // t=0.5 → 缩放 1 - 0.7*0.5 = 0.65 → 300*0.65 = 195
        assertEquals(195, RVP_HitPotionEffectService.scaledDuration(300, 4.0, 8.0f));
    }

    @Test
    void 衰减后不足二十tick归零() {
        // 基础 27t 在边缘缩放为 8.1 → 8t < 20t → 不施加
        assertEquals(0, RVP_HitPotionEffectService.scaledDuration(27, 8.0, 8.0f));
        // 基础 70t 在边缘缩放为 21t ≥ 20t → 施加
        assertEquals(21, RVP_HitPotionEffectService.scaledDuration(70, 8.0, 8.0f));
    }

    @Test
    void 非法半径归零() {
        assertEquals(0, RVP_HitPotionEffectService.scaledDuration(300, 0.0, 0.0f));
    }

    @Test
    void 单调递减() {
        int prev = RVP_HitPotionEffectService.scaledDuration(300, 0.0, 8.0f);
        for (double d = 0.5; d <= 8.0; d += 0.5) {
            int cur = RVP_HitPotionEffectService.scaledDuration(300, d, 8.0f);
            assertTrue(cur <= prev, "距离 " + d + " 处时长应不增：prev=" + prev + " cur=" + cur);
            prev = cur;
        }
    }
}
