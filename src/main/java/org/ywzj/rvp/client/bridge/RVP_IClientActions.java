package org.ywzj.rvp.client.bridge;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 公共弹体可安全调用的客户端动作接口；服务端实现不引用任何纯客户端类型。 */
public interface RVP_IClientActions {

    /** 转交既有载具鼠标入口；电视导弹优先，其次焦点锁定；服务端恒返回 false。 */
    boolean handleVehiclePlayerTurn(double yawInput, double pitchInput);

    /** 焦点切换完成后保留真实命中语义；专用服务端为空操作。 */
    void onFocusLockChanged(org.ywzj.vehicle.vehicle.part.WeaponUnit station);

    /** 接收虚拟弹体目标完整集合；专用服务端为空操作。 */
    void acceptVirtualMissileTargets(org.ywzj.rvp.network.S2CVirtualMissileTargets message);

    /** 解析客户端远程雷达对象；专用服务端返回 null。 */
    @org.jetbrains.annotations.Nullable
    net.minecraft.world.entity.Entity resolveRemoteRadarTarget(int entityId);

    /** 接收未追踪载具的导弹发射声兜底消息；专用服务端实现为空操作。 */
    void acceptVehicleSound(org.ywzj.rvp.network.S2CVehicleSound message);

    /** 接收服务端小口径划空或方块命中声；专用服务端实现为空操作。 */
    void acceptSmallCaliberSound(org.ywzj.rvp.network.S2CSmallCaliberSound message);

    /** 接收载具表面命中火花；服务端空实现不加载客户端粒子类。 */
    void acceptVehicleImpactSparks(org.ywzj.rvp.network.S2CVehicleImpactSparks message);

    /** 接收服务端地面载具击毁时间表；专用服务端实现为空操作。 */
    void acceptWreckDestructionState(org.ywzj.rvp.network.S2CWreckDestructionState message);

    /** 按客户端实体 Tick 更新纯粒子弹体表现。 */
    void tickParticleProjectile(RVP_BaseBullet projectile);

    /** 本体载具击毁烟被客户端注入点屏蔽后，保留同一方法中原有的水面气泡效果。 */
    void preserveDestroyedVehicleWaterBubbles(AbstractVehicle vehicle);

    /** 查询地面载具是否正在 RVP 殉燃窗口内，以替换本体 FIRE_AMBIENT 余火声。 */
    boolean shouldSuppressDestroyedVehicleBurning(AbstractVehicle vehicle);

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
     * @param holdTicks 距发动机燃尽的 tick 数（凝结云 alpha 保持期绑定燃烧期：
     *                  保持至 max(holdTicks+20, JSON 保持期保底) 后缓缓散开）
     * @param holdMinTicks     凝结云保持期保底（Tick），负数表示未配置、用代码默认 108
     * @param fadeTicks        淡出基础时长（Tick），负数表示未配置、用代码默认 120
     * @param fadeRandomTicks  淡出随机增量上限（Tick），负数表示未配置、用代码默认 60
     */
    void addRocketFlameTrailParticle(double x, double y, double z,
                                     double mx, double my, double mz, float sizeScale, int holdTicks,
                                     int holdMinTicks, int fadeTicks, int fadeRandomTicks,
                                     float alphaHoldRatio);

    /** 登记新的世界空间火箭尾烟；scale≤0 表示只断开后续路径，专用服务端不加载渲染类。 */
    void addRocketVolumeTrail(RVP_BaseBullet projectile, net.minecraft.world.phys.Vec3 position,
                              net.minecraft.world.phys.Vec3 exhaust, float scale, int remainingBurnTicks);

    /** 登记体积贴地烟浪，代替 rvp_rocket_flame 原有的 WASH 贴图粒子。 */
    void addRocketVolumeWash(RVP_BaseBullet projectile, net.minecraft.world.phys.Vec3 position, float scale);

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

    /**
     * 生成一枚热诱弹（flare）拖尾烟粒子。
     *
     * <p>公共实体 {@code RVP_DecoyEntity} 只在客户端 tick 中调用本方法；服务端实现为
     * NOOP——粒子是纯客户端表现，公共实体类不得直接引用 {@code @OnlyIn(CLIENT)} 的粒子类。
     * 客户端实现复用 {@code textures/wreck_cookoff/particle/} 的殉燃贴图集。</p>
     *
     * @param x         粒子出生 X 坐标
     * @param y         粒子出生 Y 坐标
     * @param z         粒子出生 Z 坐标
     * @param sizeScale 尺寸倍率，1 为默认观感
     */
    void addFlareTrailSmokeParticle(double x, double y, double z, float sizeScale);

    /**
     * [RVP] 查询本地操作者是否处于 SCOPE（开镜）视角。
     *
     * <p>目的：公共侧解析 eo_ccip 武器的弹着点锚定方向时需要判断"是否开镜"，
     * 而公共代码不得直接引用 @OnlyIn(CLIENT) 的 LocalVehiclePlayer，故经本桥接查询。
     * 专用服务端为 NOOP，恒返回 false。</p>
     *
     * @return 本地玩家正在操作载具且视角为 SCOPE 时 true
     */
    boolean isLocalOperatorInScopeView();
}
