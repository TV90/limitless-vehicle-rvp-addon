package org.ywzj.rvp.client.bridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.particle.RVP_ParticleProjectileEmitter;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 客户端真实动作实现。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_ClientActions implements RVP_IClientActions {

    @Override
    public void tickParticleProjectile(RVP_BaseBullet projectile) {
        // 调用本项目粒子弹体发射器：按 Tick 生成主体及历史路径采样点。
        RVP_ParticleProjectileEmitter.tick(projectile);
    }
}
