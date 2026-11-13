package org.ywzj.rvp.weapon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_WeaponHeatManagerTest {

    @Test
    void overlappingClientAndServerClocksCoolEachTickOnce() throws ReflectiveOperationException {
        var state = new RVP_WeaponHeatManager.HeatState();
        var heat = RVP_WeaponHeatManager.HeatState.class.getDeclaredField("currentHeat");
        var cool = RVP_WeaponHeatManager.class.getDeclaredMethod(
                "tickState", RVP_WeaponHeatManager.HeatState.class, long.class, double.class);
        heat.setAccessible(true);
        cool.setAccessible(true);
        heat.setInt(state, 140);
        cool.invoke(null, state, 1_000L, 0.0);

        // 模拟服务端推进后，尚落后一刻的客户端先绘制 HUD，再追上该游戏刻。
        for (long tick = 1_001L; tick <= 1_091L; tick++) {
            cool.invoke(null, state, tick, 0.0);
            cool.invoke(null, state, tick - 1, 0.0);
            cool.invoke(null, state, tick, 0.0);
        }
        assertEquals(49, heat.getInt(state));
    }
}
