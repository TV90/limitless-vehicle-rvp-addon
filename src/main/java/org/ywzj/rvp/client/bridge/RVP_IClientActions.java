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

    /**
     * 生成一个 HBM 风格火箭尾焰粒子（弹道导弹"先火后烟"飞行尾迹专用）。
     *
     * <p>同 {@link #addTrailSmokeParticle}：不经 provider 查表，直接构造
     * {@code RVP_RocketFlameParticle}（TRAIL 模式，HBM ParticleRocketFlame 移植）后加入引擎。</p>
     *
     * @param mx/my/mz 粒子初速（弹轴反方向，由调用方按 -lookAngle × 1.0 计算）
     * @param sizeScale 尺寸倍率（对标本体 getContrailScale：大弹 1.0 / 小弹 0.5）
     */
    void addRocketFlameTrailParticle(double x, double y, double z,
                                     double mx, double my, double mz, float sizeScale);

    /**
     * 生成一个 HBM 风格液氧煤油黑烟尾焰粒子（技术储备：09-19 前的原始黑烟观感）。
     *
     * @param mx/my/mz 粒子初速（弹轴反方向，由调用方按 -lookAngle × 1.0 计算）
     * @param sizeScale 尺寸倍率（effects_data.missile_native_trail_particle_scale × launch_boost）
     */
    void addKeroseneBlackSmokeTrailParticle(double x, double y, double z,
                                            double mx, double my, double mz, float sizeScale);

    /**
     * 生成一个 HBM 风格发射地面烟浪粒子（起飞贴地横向冲刷灰烟，ParticleSmokePlume 移植）。
     * 初速由粒子内部随机（水平径向 0.5~0.9 + 微升），调用方只需给出生成点与尺寸倍率。
     */
    void addLaunchWashParticle(double x, double y, double z, float sizeScale);

    /**
     * 查询世界坐标是否落在人在回路（HITL）激活导弹的粒子屏蔽半径内（TV 导弹视角下屏蔽自身尾焰，
     * 避免尾焰烟遮挡弹载相机画面）。
     *
     * @return 客户端返回屏蔽判定结果；专用服 NOOP 恒 false。
     */
    boolean shouldSuppressTrailParticleNearHitlMissile(double x, double y, double z);
}
