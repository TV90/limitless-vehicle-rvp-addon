package org.ywzj.rvp.client.particle;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

import java.lang.ref.WeakReference;

/** 客户端同一弹体尾迹共用的落地后寿命计时门。 */
final class RVP_TrailLifetimeGate {

    /** 对应权威弹体的弱引用，避免尾迹粒子延长已移除实体的生命周期。 */
    private final WeakReference<RVP_BaseBullet> projectileReference;
    /** 是否已经检测到落地、弹体结束或客户端追踪释放。 */
    private boolean released;

    RVP_TrailLifetimeGate(RVP_BaseBullet projectile) {
        this.projectileReference = new WeakReference<>(projectile);
    }

    /**
     * 判断尾迹寿命是否仍应暂停；弹体落地、结束或引用释放后永久打开计时门。
     */
    boolean shouldPauseLifetime() {
        if (!released) {
            RVP_BaseBullet projectile = projectileReference.get();
            released = projectile == null || !projectile.isAlive() || projectile.onGround();
        }
        return !released;
    }
}
