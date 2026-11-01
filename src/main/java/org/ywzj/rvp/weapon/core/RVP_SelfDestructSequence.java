package org.ywzj.rvp.weapon.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** 主线程自爆事务：回传确认 → 扣弹/提交 → 一次战斗部 → 终结清理。 */
final class RVP_SelfDestructSequence {
    /** 正在执行的载具；阻断爆炸回调、相邻武器站造成的重入。 */
    private static final Set<UUID> EXECUTING = new HashSet<>();

    private RVP_SelfDestructSequence() {}

    static boolean execute(UUID vehicle, BooleanSupplier returnPlayers, BooleanSupplier commit,
                           Runnable detonate, Runnable finish) {
        if (!EXECUTING.add(vehicle)) return false;
        try {
            if (!returnPlayers.getAsBoolean() || !commit.getAsBoolean()) return false;
            try {
                detonate.run();
            } finally {
                // 爆炸一旦提交，不因特效异常留下可再次起爆的活载具。
                finish.run();
            }
            return true;
        } finally {
            EXECUTING.remove(vehicle);
        }
    }
}
