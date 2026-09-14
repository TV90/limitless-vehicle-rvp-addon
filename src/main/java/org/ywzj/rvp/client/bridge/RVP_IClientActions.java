package org.ywzj.rvp.client.bridge;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

/** 公共弹体可安全调用的客户端动作接口；服务端实现不引用任何纯客户端类型。 */
public interface RVP_IClientActions {

    /** 按客户端实体 Tick 更新纯粒子弹体表现。 */
    void tickParticleProjectile(RVP_BaseBullet projectile);

    /** 请求客户端打开炮火支援终端上下文；阶段 D 安装实际地图工具前允许安全无操作。 */
    void openFireSupportTerminal();

    /** 查询客户端已同步的 profile 是否仍可用；同步完成前返回 true，避免旧物品短暂闪烁失效。 */
    boolean isFireSupportProfileAvailable(ResourceLocation profileId);

    /**
     * 生成一个可指定渲染尺寸倍率的粒子（尾迹用）。
     *
     * <p>原版 {@code Level.addParticle(...)} 既不返回粒子实例、也无法指定渲染尺寸，
     * 故该动作在客户端侧自行创建粒子并缩放其 {@code quadSize}（单面粒子的渲染尺寸）。</p>
     *
     * @param options 粒子选项（其类型决定外观）
     * @param scale   渲染尺寸倍率；{@code <= 1} 时行为与 {@code addParticle(..., true, ...)} 等价
     */
    void addScaledParticle(net.minecraft.core.particles.ParticleOptions options,
                           double x, double y, double z, float scale);

    /**
     * 生成一个本项目 MCHR 风格翻滚烟团（尾迹专用，尺寸/颜色/寿命/扩散全部内置）。
     *
     * <p>与 {@link #addScaledParticle} 的区别：不经过原版粒子类型与 {@code ParticleEngine} 的 provider 查表，
     * 而是直接构造自定义粒子实例后加入引擎，因此尺寸等参数完全可控
     * （观感取自 MCHR 的 {@code MCH_EntityParticleSmoke}：8 帧消散、天空光全亮、每 tick 扩散）。</p>
     *
     * @param sizeScale 烟团尺寸倍率；{@code 1} = 拖烟默认观感
     */
    void addTrailSmokeParticle(double x, double y, double z, float sizeScale);
}
