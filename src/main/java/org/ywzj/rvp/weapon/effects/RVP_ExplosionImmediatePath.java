package org.ywzj.rvp.weapon.effects;

/**
 * "RVP 弹体爆炸"窗口标记：激活期间，本模组的
 * {@code VehicleExplosionImmediatePathMixin} 会把本体 {@code VehicleExplosion}
 * 的批量破坏半径阈值（{@code BATCHED_DESTRUCTION_RADIUS_THRESHOLD = 32}）抬到
 * 正无穷——即 RVP 武器爆炸无论半径多大都走 ≤32 的即时破坏路径
 * （GridCollectionTask + destroyBlocksImmediately，单 tick 完成、无烧灼转化、
 * 无跨 tick 服务端持续负载），不再进入核爆炸批处理（SphericalCollectionTask
 * 分 tick 扫描 + 烧灼方块替换，实测为 ">32 爆炸卡顿"来源，2026-09-20 用户定版绕开）。
 *
 * <p>由 {@code RVP_BaseBullet.triggerExplosion} 在执行爆炸前包住整个爆炸调用
 * （含 destroy_radius 拆分的双爆炸）——{@code explode()} 的路径分岔在该同步窗口内
 * 决策，ThreadLocal 覆盖充分。仅影响 RVP 弹体自己的爆炸；本体/其它 mod 的
 * {@code VehicleExplosion} 不经本窗口，行为不变。可用配置
 * {@code forceImmediateExplosionDestruction}（默认 true）整体关闭。</p>
 */
public final class RVP_ExplosionImmediatePath {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RVP_ExplosionImmediatePath() {}

    public static boolean active() {
        return ACTIVE.get();
    }

    public static void run(Runnable action) {
        ACTIVE.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            ACTIVE.remove();
        }
    }
}
