package org.ywzj.rvp.client.bridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.particle.RVP_ParticleProjectileEmitter;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.firesupport.RVP_FireSupportMapTool;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import net.minecraft.client.Minecraft;

/** 客户端真实动作实现。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_ClientActions implements RVP_IClientActions {

    @Override
    public void tickParticleProjectile(RVP_BaseBullet projectile) {
        // 调用本项目粒子弹体发射器：按 Tick 生成主体及历史路径采样点。
        RVP_ParticleProjectileEmitter.tick(projectile);
    }

    @Override
    public void openFireSupportTerminal() {
        // 调用本项目战术地图 Screen：保留普通地图画布并安装独立炮火工具，不进入载具 ARTILLERY 链路。
        Minecraft.getInstance().setScreen(new RVP_TacticalMapScreen(
                RVP_TacticalMapScreen.MapMode.TACTICAL, new RVP_FireSupportMapTool()));
    }

    @Override
    public boolean isFireSupportProfileAvailable(ResourceLocation profileId) {
        // 调用本项目客户端 profile 状态：reload 删除 profile 后让已有变体显示失效配置。
        return RVP_ClientFireSupportState.INSTANCE.isProfileAvailable(profileId);
    }
}
