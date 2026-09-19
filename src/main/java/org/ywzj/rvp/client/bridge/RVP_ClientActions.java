package org.ywzj.rvp.client.bridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_ParticleProjectileEmitter;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportState;
import org.ywzj.rvp.client.firesupport.RVP_FireSupportMapTool;
import org.ywzj.rvp.client.screen.RVP_TacticalMapScreen;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;

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

    /**
     * 生成可指定渲染尺寸倍率的粒子。
     *
     * <p>实现依据（已核对 1.20.1 源码）：</p>
     * <ul>
     *   <li>{@code LevelRenderer.addParticleInternal} 在 {@code force=true} 时**直接**
     *       调 {@code particleEngine.createParticle(...)}（跳过距离裁剪），故本方法与此前
     *       {@code level().addParticle(options, true, ...)} 路径等价；</li>
     *   <li>{@code ParticleEngine.createParticle(...)} 为 public 且返回实例，其内部已把粒子
     *       加入待 Tick 队列，因此在同一次调用后立刻缩放，顺序安全（队列在后续 tick 才生效）；</li>
     *   <li>{@code SingleQuadParticle} 覆盖了 {@code Particle#scale(float)}，执行
     *       {@code quadSize *= pScale}（quadSize 即单面粒子的渲染尺寸）；</li>
     *   <li>{@code CampfireSmokeParticle.tick()} 不重设 quadSize，故单次缩放即可保持到粒子消亡。</li>
     * </ul>
     */
    @Override
    public void addScaledParticle(ParticleOptions options, double x, double y, double z, float scale) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(options, x, y, z, 0.0D, 0.0D, 0.0D);
        // scale <= 0 或 == 1：保持原尺寸（createParticle 已完成与 addParticle(force=true) 等价的生成）
        if (particle != null && scale > 0f && scale != 1f) {
            particle.scale(scale);
        }
    }

    /**
     * 生成 MCHR 风格翻滚烟团作为尾迹粒子。
     *
     * <p>不走 {@code ParticleEngine.createParticle}（那需要已注册的 {@code ParticleType} + provider，
     * 且无法携带尺寸），而是复用视觉工厂的写法：直接静态构造自定义粒子实例（尺寸烘进构造），
     * 再 {@code engine.add(...)}——与 {@code RVP_DefaultExplosionEffectFactory} 生成爆炸烟同一条路径。</p>
     */
    @Override
    public void addTrailSmokeParticle(double x, double y, double z, float sizeScale) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                RVP_MchrSmokeParticle.ofTrailScaled(level, x, y, z, sizeScale));
    }

    /** 生成 HBM 风格火箭尾焰粒子（TRAIL 模式，先火后烟膨胀柱，参数见粒子类移植注释；
     *  holdTicks 为距发动机燃尽的 tick 数，凝结云保持期据此绑定燃烧期）。 */
    @Override
    public void addRocketFlameTrailParticle(double x, double y, double z,
                                            double mx, double my, double mz, float sizeScale,
                                            int holdTicks) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_RocketFlameParticle.ofTrail(
                        level, x, y, z, mx, my, mz, sizeScale, holdTicks));
    }

    /** 生成 HBM 风格液氧煤油黑烟尾焰粒子（技术储备款，TRAIL 模式，09-19 前原始黑烟观感）。 */
    @Override
    public void addKeroseneBlackSmokeTrailParticle(double x, double y, double z,
                                                   double mx, double my, double mz, float sizeScale) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_RocketFlameParticle.ofKeroseneBlackSmokeTrail(
                        level, x, y, z, mx, my, mz, sizeScale));
    }

    /** 生成 HBM 风格发射地面烟浪粒子（WASH 模式，初速内部随机径向冲刷 + 浮升）。 */
    @Override
    public void addLaunchWashParticle(double x, double y, double z, float sizeScale) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Minecraft.getInstance().particleEngine.add(
                org.ywzj.rvp.client.particle.RVP_RocketFlameParticle.ofLaunchWash(
                        level, x, y, z, sizeScale));
    }

    /** HITL 粒子屏蔽查询：委托 RVP_ClientHitlState（激活导弹半径内返回 true）。 */
    @Override
    public boolean shouldSuppressTrailParticleNearHitlMissile(double x, double y, double z) {
        return org.ywzj.rvp.client.state.RVP_ClientHitlState.shouldSuppressParticleNearActiveMissile(x, y, z);
    }
}
