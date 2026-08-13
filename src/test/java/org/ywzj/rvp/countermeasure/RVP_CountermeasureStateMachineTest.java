package org.ywzj.rvp.countermeasure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_CountermeasureStateMachineTest {

    /** 充足时恰好发射 n 轮 × m 发，不进入装填。 */
    @Test
    void enoughAmmoFiresExactlyFullBurst() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(64, 4, 8, 4, 200);
        m.onKeyPress();
        assertTrue(m.isFiring());
        int fired = 0;
        int rounds = 0;
        for (int t = 0; t < 100 && rounds < 8; t++) {
            int n = m.onTick();
            if (n > 0) {
                rounds++;
            }
            fired += n;
        }
        assertEquals(8, rounds);
        assertEquals(32, fired);
        assertEquals(32, m.getRemaining());
        assertFalse(m.isFiring());
        assertFalse(m.isReloading());
    }

    /** 剩余不足时 ceil(R/m) 轮耗尽，末轮不足 m，随后进入装填。 */
    @Test
    void insufficientAmmoDrainsAllThenReloads() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(10, 4, 8, 4, 200);
        m.onKeyPress();
        // ceil(10/4) = 3 轮：4 + 4 + 2
        assertEquals(4, m.onTick());
        assertEquals(6, m.getRemaining());
        assertEquals(4, tickThroughInterval(m, 4));
        assertEquals(2, tickThroughInterval(m, 4));
        assertEquals(0, m.getRemaining());
        assertFalse(m.isFiring());
        assertTrue(m.isReloading());
    }

    /** 耗尽后装填 reloadTick 恢复至 total。 */
    @Test
    void reloadRestoresToTotal() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(10, 4, 8, 4, 50);
        m.onKeyPress();
        m.onTick(); // 4
        tickThroughInterval(m, 4); // 4
        tickThroughInterval(m, 4); // 2 -> remaining 0, reloading
        assertTrue(m.isReloading());
        for (int i = 0; i < 50; i++) {
            m.onTick();
        }
        assertEquals(10, m.getRemaining());
        assertFalse(m.isReloading());
    }

    /** 齐射中再次按键被忽略。 */
    @Test
    void pressIgnoredWhileFiring() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(64, 4, 8, 4, 200);
        m.onKeyPress();
        m.onTick(); // 第一轮
        assertEquals(60, m.getRemaining());
        m.onKeyPress(); // 齐射中，应忽略
        assertEquals(60, m.getRemaining());
    }

    /** 装填中按键被忽略。 */
    @Test
    void pressIgnoredWhileReloading() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(6, 4, 8, 4, 20);
        m.onKeyPress();
        m.onTick(); // 4
        tickThroughInterval(m, 4); // 2 -> remaining 0, reloading
        assertTrue(m.isReloading());
        m.onKeyPress();
        assertEquals(0, m.getRemaining());
        assertTrue(m.isReloading());
    }

    /** 弹舱为 0 时系统禁用，按键无效果。 */
    @Test
    void disabledSystemIgnoresPress() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(0, 4, 8, 4, 200);
        assertFalse(m.isEnabled());
        m.onKeyPress();
        assertFalse(m.isFiring());
        assertEquals(0, m.onTick());
    }

    /** 剩余为 0 时按键被忽略。 */
    @Test
    void pressIgnoredWhenEmpty() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(1, 4, 8, 4, 20);
        m.onKeyPress();
        assertEquals(1, m.onTick()); // 单发
        assertEquals(0, m.getRemaining());
        assertTrue(m.isReloading());
        m.onKeyPress();
        assertFalse(m.isFiring());
        assertEquals(0, m.getRemaining());
    }

    /** 间隔 tick 内 onTick 不发射。 */
    @Test
    void intervalTicksDoNotFire() {
        RVP_CountermeasureStateMachine m = new RVP_CountermeasureStateMachine(64, 4, 8, 4, 200);
        m.onKeyPress();
        assertEquals(4, m.onTick()); // 第一轮
        for (int i = 0; i < 4; i++) {
            assertEquals(0, m.onTick()); // 4 个间隔 tick
        }
        assertEquals(4, m.onTick()); // 第二轮
    }

    /** 跳过 launchIntervalTick 个间隔 tick 后，下一次 onTick 触发下一轮发射。 */
    private static int tickThroughInterval(RVP_CountermeasureStateMachine m, int interval) {
        for (int i = 0; i < interval; i++) {
            m.onTick();
        }
        return m.onTick();
    }
}
