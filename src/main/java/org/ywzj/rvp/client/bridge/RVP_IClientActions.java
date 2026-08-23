package org.ywzj.rvp.client.bridge;

import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 公共弹体可安全调用的客户端动作接口；服务端实现不引用任何纯客户端类型。 */
public interface RVP_IClientActions {

    /** 按客户端实体 Tick 更新纯粒子弹体表现。 */
    void tickParticleProjectile(RVP_BaseBullet projectile);
}
