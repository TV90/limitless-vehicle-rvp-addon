package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * Projectile lifecycle VFX parameters.
 */
public class RVP_EffectsData {

    /** 飞行轨迹粒子短名或资源 ID，默认 {@code minecraft:cloud}；写 {@code none} 时关闭。 */
    @SerializedName("trajectory_particle")
    private String trajectoryParticle = "minecraft:cloud";

    /** 是否启用导弹原生尾迹，默认 null（按 true 处理）；仅导弹客户端尾迹生效。 */
    @SerializedName("missile_native_trail_enabled")
    private Boolean missileNativeTrailEnabled;

    /** 导弹原生尾迹粒子覆盖 ID，默认空；非空时替代默认营火烟。 */
    @SerializedName("missile_native_trail_particle")
    private String missileNativeTrailParticle = "";

    /**
     * 导弹尾迹沿路径采样间距，单位格，默认 0.5；启用原生尾迹时最小为 0.05。
     * {@code rvp_rocket_flame} 的体积烟将其换算为有界烟段细分精度，不再按此间距生成贴图粒子。
     */
    @SerializedName("missile_native_trail_step")
    private Float missileNativeTrailStep;

    /** 导弹尾迹生成间隔，单位 Tick，默认 1；启用原生尾迹时至少为 1。 */
    @SerializedName("missile_native_trail_spawn_interval_tick")
    private Integer missileNativeTrailSpawnIntervalTick;

    /**
     * 导弹尾迹采样密度倍率，默认 1；启用原生尾迹时限制为非负有限值。
     * {@code rvp_rocket_flame} 体积烟用它控制空间烟密度（上限 16），零关闭新增烟段；其它风格仍控制粒子数量。
     */
    @SerializedName("missile_native_trail_density_scale")
    private Float missileNativeTrailDensityScale;

    /** 导弹尾迹相对弹体尾部偏移，单位格，默认 3；启用原生尾迹时限制为非负。 */
    @SerializedName("missile_native_trail_offset")
    private Float missileNativeTrailOffset;

    /**
     * 导弹尾迹粒子渲染尺寸倍率，默认 {@code 1}（原尺寸）。
     *
     * <p>本项让尾迹变粗；{@code rvp_rocket_flame} 体积烟按它缩放世界半径（最大 64 倍），
     * 其它风格按它放大单个粒子的渲染尺寸。与密度项独立，近距和 RVP 远距尾迹均生效
     * （服务端广播的 {@code trajectory_particle} 走原版 {@code sendParticles}，无法携带尺寸）。</p>
     */
    @SerializedName("missile_native_trail_particle_scale")
    private Float missileNativeTrailParticleScale;

    /**
     * 尾迹粒子风格，默认空（按 {@code vanilla} 处理）。
     *
     * <ul>
     *   <li>空 / {@code vanilla}：用原版粒子（类型由 {@code missile_native_trail_particle} 指定），
     *       尺寸经 {@code Particle#scale} 缩放——只放大原版 sprite，观感受限于原版；</li>
     *   <li>{@code rvp_smoke}：改用本项目 MCHR 风格翻滚烟团（`RVP_MchrSmokeParticle`，从
     *       MCHR 的 `MCH_EntityParticleSmoke` 逐条移植）——尺寸直接烘进构造、8 帧消散动画、
     *       天空光全亮，观感更接近真实导弹尾迹，且不依赖原版粒子的缩放行为。</li>
     *   <li>{@code rvp_rocket_flame}：世界轨迹体积烟，保留路径后逐像素计算连续烟柱，
     *       无烟雾图片；尺寸、发射段加粗、保持期、alpha 比例和淡出字段继续生效。
     *       喷口火焰由独立程序喷焰负责；旧 HBM 贴图粒子代码由代码开关停用但完整保留。
     *       默认启用程序体积贴地烟浪（见 {@code missile_native_trail_ground_wash}）；</li>
     *   <li>{@code rvp_kerosene_black_smoke}：液氧煤油黑烟款（同粒子类，技术储备）——09-19 前的
     *       原始观感：寿命 45~65t、深灰黑烟（0.15~0.30）、全程平方根淡出；贴地烟浪推导同开。</li>
     * </ul>
     */
    @SerializedName("missile_native_trail_particle_style")
    private String missileNativeTrailParticleStyle = "";

    /**
     * 发射段贴地烟浪开关，默认 null（按风格推导：{@code rvp_rocket_flame} 时开启、其余关闭）。
     *
     * <p>对齐 HBM 发射台 {@code launchSmoke}：导弹发动机燃烧且距地高度不足
     * {@code 20} 格时（也含冷发射弹射段），客户端在弹体地面投影点生成横向冲刷的灰烟。
     * {@code rvp_rocket_flame} 当前每 tick 两团程序体积烟；其它显式开启旧烟浪的风格仍沿用八颗粒子。
     * 半径 0.3 → 3.0 × {@code missile_native_trail_particle_scale}，膨胀带缓慢浮升。</p>
     */
    @SerializedName("missile_native_trail_ground_wash")
    private Boolean missileNativeTrailGroundWash;

    /**
     * 发射段烟柱加粗倍率，默认 {@code 1.0}（关闭）。
     *
     * <p>只影响发射段：粒子尺寸倍率在<b>一级燃烧窗口内</b>（同步的 {@code motorBurnEndTick}，
     * 即点火延迟 + 一级燃烧时长）随飞行进度线性回落到 1.0——发射时全额加粗、一级燃尽恢复
     * 常规粗细，中段/末段尾迹不受影响。最终尺寸 = {@code missile_native_trail_particle_scale}
     * × 本倍率（随进度衰减）。</p>
     */
    @SerializedName("missile_native_trail_launch_boost")
    private Float missileNativeTrailLaunchBoost;

    /** 是否在推进燃烧期追加火焰，默认 null（按 true 处理）；仅推进弹体客户端表现生效。 */
    @SerializedName("missile_native_trail_extra_flame")
    private Boolean missileNativeTrailExtraFlame;

    /** 是否在推进燃烧期追加烟尘，默认 null（按 true 处理）；仅推进弹体客户端表现生效。 */
    @SerializedName("missile_native_trail_extra_smoke")
    private Boolean missileNativeTrailExtraSmoke;

    /**
     * 尾迹凝结云保持期保底时长，单位 Tick，默认 {@code null}（沿用代码默认 108）。
     *
     * <p>仅 {@code rvp_rocket_flame} 风格生效。保持期取剩余燃烧时间加缓冲与本值中的较大者，
     * 再按总寿命预算为淡出预留时间。默认保底 108t，总寿命上限 300t。</p>
     */
    @SerializedName("missile_native_trail_hold_min_ticks")
    private Integer missileNativeTrailHoldMinTicks;

    /**
     * 尾迹凝结云 **alpha 保持期占寿命的比例**，默认 {@code null}（沿用代码默认 1.0）。
     *
     * <p>仅 {@code rvp_rocket_flame} 风格生效。体积烟在保持期内维持配置密度，
     * 屏幕透明度由视线穿过的烟厚度计算，不再固定为旧粒子的 {@code 0.75}。
     * 本项把开始淡出的时间从总寿命中独立出来：</p>
     *
     * <pre>
     * alpha 保持 = min(保持期, 总寿命 × 本比例)
     * </pre>
     *
     * <p>{@code 1.0} = 保持到保持期末（原始行为）；{@code 0.3} = 只在前 30% 寿命浓着，
     * 剩下 70% 一路淡出，视觉"存在感"大幅缩短。写 {@code 0} 则从出生就开始淡。</p>
     */
    @SerializedName("missile_native_trail_alpha_hold_ratio")
    private Float missileNativeTrailAlphaHoldRatio;

    /**
     * 尾迹凝结云淡出基础时长，单位 Tick，默认 {@code null}（沿用代码默认 120）。
     * 仅 {@code rvp_rocket_flame} 风格生效；与 {@code missile_native_trail_fade_random_ticks}
     * 相加构成淡出期（smoothstep 散开）。总寿命 = 保持期 + 淡出期。
     */
    @SerializedName("missile_native_trail_fade_ticks")
    private Integer missileNativeTrailFadeTicks;

    /**
     * 尾迹凝结云淡出随机增量上限，单位 Tick，默认 {@code null}（沿用代码默认 60）。
     * 仅 {@code rvp_rocket_flame} 风格生效；每个粒子在 {@code [0, 本值)} 内随机延寿，
     * 让同一段尾迹的散开不齐、避免整条云同时消失。写 {@code 0} 则完全同步散开。
     */
    @SerializedName("missile_native_trail_fade_random_ticks")
    private Integer missileNativeTrailFadeRandomTicks;

    /** 命中粒子短名或资源 ID，默认空（使用方块命中默认表现）；写 {@code none} 时关闭。 */
    @SerializedName("impact_particle")
    private String impactParticle = "";

    /**
     * 表面命中火花开关，无单位，默认 true（未配置的弹种也生效，显式 false 可单独关闭）。
     * 实体直接碰撞载具（含跳弹）与方块表面命中均触发；生物命中、爆炸波及与近炸不触发。
     * 火花尺寸、数量与飞溅速度由 caliber（毫米）决定，不使用 tracer_caliber，
     * 与 impact_particle 的方块命中效果独立；本字段只控制视觉，不改变穿甲或伤害能力。
     */
    @SerializedName("vehicle_impact_sparks")
    private boolean vehicleImpactSparks = true;

    /** 是否为本弹药启用表面命中火星（载具与方块）；不按武器名称推断弹种。 */
    public boolean isVehicleImpactSparks() {
        return vehicleImpactSparks;
    }

    /** 爆炸附加粒子短名或资源 ID，默认空（使用原版爆炸粒子）；写 {@code none} 时关闭。 */
    @SerializedName("explosion_particle")
    private String explosionParticle = "";

    /** 弹体命中瞬间即死亡（飞行时间过短、从未广播过轨迹粒子）时，在命中点补渲轨迹粒子簇。 */
    @SerializedName("impact_trail_particles")
    private Boolean impactTrailParticles;

    /**
     * 穿透丢弃路径（on_penetrate parent_action，PELE 等）专用命中补渲开关；
     * 与 impact_trail_particles 互不联动，缺省关——该路径轨迹簇观感欠佳，仅 PELE 类子弹药显式开启。
     */
    @SerializedName("penetrate_impact_trail_particles")
    private Boolean penetrateImpactTrailParticles;

    /** 方块破碎粒子基数，单位个，默认 10；非负值仅在默认方块命中粒子模式生效。 */
    @SerializedName("flak_particles_crack")
    private int flakParticlesCrack = 10;

    /** 命中白烟粒子数量，单位个，默认 3；非负值仅在默认方块命中粒子模式生效。 */
    @SerializedName("num_particles_flak")
    private int numParticlesFlak = 3;

    /** 方块破碎粒子速度散布，单位格/Tick，默认 0.3；默认方块命中粒子模式生效。 */
    @SerializedName("flak_particles_diff")
    private float flakParticlesDiff = 0.3f;

    /** 弹体口径，单位毫米，默认 null；未配置或非正值时按 7.62 mm 回退。 */
    @SerializedName("caliber")
    private Float caliber;

    /**
     * 曳光口径/弹体视觉口径，单位毫米，默认 null；未配置或非正值时回退 caliber，再回退 7.62 mm。
     * 仅供 RVP 弹体渲染计算曳光宽度，不参与弹道物理、炮口烟或殉燃判定。
     */
    @SerializedName("tracer_caliber")
    private Float tracerCaliber;

    /** 机枪曳光红色通道，范围通常 0～1，默认 null（按 1）；仅机枪 Renderer 生效。 */
    @SerializedName("tracer_r")
    private Float tracerR;

    /** 机枪曳光绿色通道，范围通常 0～1，默认 null（按 0.85）；仅机枪 Renderer 生效。 */
    @SerializedName("tracer_g")
    private Float tracerG;

    /** 机枪曳光蓝色通道，范围通常 0～1，默认 null（按 0.2）；仅机枪 Renderer 生效。 */
    @SerializedName("tracer_b")
    private Float tracerB;

    /**
     * 机枪曳光长度倍率，默认 1.0（= 现状）。
     * 生效公式：长度 = min(0.3 × 当前速度 × 本倍率, 已飞距离 × 0.8) 格。
     * 0 表示不显示曳光；非法值（NaN/无穷）按 1.0 处理；上限 8。
     */
    @SerializedName("tracer_length_scale")
    private Float tracerLengthScale;

    /** 线导视觉线：导弹与发射枢轴间绘制原版钓鱼线风格线缆。 */
    @SerializedName("wire_link_enabled")
    private Boolean wireLinkEnabled;

    /** 纯粒子弹体视觉配置，默认禁用；启用时类型化 Renderer 跳过模型并由客户端 Tick 生成粒子。 */
    @SerializedName("particle_projectile_data")
    private RVP_ParticleProjectileData particleProjectileData = new RVP_ParticleProjectileData();

    /**
     * [RVP] 水中尾迹粒子 id（2026-10-02，仅 {@code rvp:torpedo} 消费）：水中巡航段每 tick
     * 沿弹后生成（原版粒子命名空间全名，如 {@code minecraft:bubble}），空气中不生成——
     * 水中观感即"雷迹气泡带"。客户端本地判定入水状态生成，零网络包。空串 = 关闭。
     */
    @SerializedName("water_trail_particle")
    private String waterTrailParticle = "minecraft:bubble";

    /**
     * [RVP] 水中尾迹每 tick 粒子数量（仅 {@code rvp:torpedo} 消费）；配置 ≤0 按默认 2 处理。
     */
    @SerializedName("water_trail_count")
    private int waterTrailCount = 2;

    /**
     * [RVP] 入水水花粒子 id（仅 {@code rvp:torpedo} 消费）：入水瞬间在入水点生成一簇
     * （默认 {@code minecraft:splash}）。客户端本地生成。空串 = 关闭。
     */
    @SerializedName("water_entry_particle")
    private String waterEntryParticle = "minecraft:splash";

    /**
     * [RVP] 入水音效 SoundEvent id（仅 {@code rvp:torpedo} 消费，如
     * {@code minecraft:entity.player.splash}）。客户端本地播放（按与爆点/声源距离衰减）。
     * 空串 = 无声。
     */
    @SerializedName("water_entry_sound")
    private String waterEntrySound = "";

    public String getTrajectoryParticle() {
        return trajectoryParticle == null ? "" : trajectoryParticle;
    }

    /** 水中尾迹粒子 id（{@code null}/空白 = 关闭水中尾迹）。 */
    public String getWaterTrailParticle() {
        return waterTrailParticle == null ? "" : waterTrailParticle;
    }

    /** 水中尾迹每 tick 粒子数量（≤0 回默认 2）。 */
    public int getWaterTrailCount() {
        return waterTrailCount > 0 ? waterTrailCount : 2;
    }

    /** 入水水花粒子 id（{@code null}/空白 = 关闭水花）。 */
    public String getWaterEntryParticle() {
        return waterEntryParticle == null ? "" : waterEntryParticle;
    }

    /** 入水音效 id（{@code null}/空白 = 无声）。 */
    public String getWaterEntrySound() {
        return waterEntrySound == null ? "" : waterEntrySound;
    }

    public boolean isMissileNativeTrailEnabled() {
        return missileNativeTrailEnabled == null || missileNativeTrailEnabled;
    }

    public boolean hasMissileNativeTrailParticleOverride() {
        return missileNativeTrailParticle != null && !missileNativeTrailParticle.isBlank();
    }

    public String getMissileNativeTrailParticle() {
        return missileNativeTrailParticle == null ? "" : missileNativeTrailParticle;
    }

    public float getMissileNativeTrailStep() {
        if (missileNativeTrailStep == null || Float.isNaN(missileNativeTrailStep) || Float.isInfinite(missileNativeTrailStep)) {
            return 0.5f;
        }
        return Math.max(missileNativeTrailStep, 0.05f);
    }

    public int getMissileNativeTrailSpawnIntervalTick() {
        return missileNativeTrailSpawnIntervalTick == null ? 1 : Math.max(missileNativeTrailSpawnIntervalTick, 1);
    }

    public float getMissileNativeTrailDensityScale() {
        if (missileNativeTrailDensityScale == null
                || Float.isNaN(missileNativeTrailDensityScale)
                || Float.isInfinite(missileNativeTrailDensityScale)) {
            return 1f;
        }
        return Math.max(missileNativeTrailDensityScale, 0f);
    }

    public float getMissileNativeTrailOffset() {
        if (missileNativeTrailOffset == null || Float.isNaN(missileNativeTrailOffset) || Float.isInfinite(missileNativeTrailOffset)) {
            return 3f;
        }
        return Math.max(missileNativeTrailOffset, 0f);
    }

    /**
     * 尾迹粒子渲染尺寸倍率；未配置或非法值返回 {@code 1}（原尺寸）。
     * 下限 0（不可为负），上限不设（由配置方自行控制在合理观感内）。
     */
    public float getMissileNativeTrailParticleScale() {
        if (missileNativeTrailParticleScale == null
                || Float.isNaN(missileNativeTrailParticleScale)
                || Float.isInfinite(missileNativeTrailParticleScale)) {
            return 1f;
        }
        return Math.max(missileNativeTrailParticleScale, 0f);
    }

    /** 尾迹粒子风格原始值（小写化）；空串表示原版粒子。 */
    public String getMissileNativeTrailParticleStyle() {
        return missileNativeTrailParticleStyle == null ? "" : missileNativeTrailParticleStyle.trim().toLowerCase();
    }

    /** 凝结云保持期保底（Tick）；未配置返回 -1，表示沿用代码默认 108。 */
    public int getMissileNativeTrailHoldMinTicks() {
        return clampTrailTicks(missileNativeTrailHoldMinTicks);
    }

    /** 凝结云 alpha 保持期比例；未配置返回 -1，表示沿用代码默认 1.0（保持到保持期末）。 */
    public float getMissileNativeTrailAlphaHoldRatio() {
        if (missileNativeTrailAlphaHoldRatio == null
                || Float.isNaN(missileNativeTrailAlphaHoldRatio)
                || Float.isInfinite(missileNativeTrailAlphaHoldRatio)) {
            return -1f;
        }
        return Math.max(0f, Math.min(missileNativeTrailAlphaHoldRatio, 1f));
    }

    /** 凝结云淡出基础时长（Tick）；未配置返回 -1，表示沿用代码默认 120。 */
    public int getMissileNativeTrailFadeTicks() {
        return clampTrailTicks(missileNativeTrailFadeTicks);
    }

    /** 凝结云淡出随机增量上限（Tick）；未配置返回 -1，表示沿用代码默认 60。 */
    public int getMissileNativeTrailFadeRandomTicks() {
        return clampTrailTicks(missileNativeTrailFadeRandomTicks);
    }

    /** 尾迹时长类字段的统一钳制：null → -1（沿用代码默认），其余限制在 [0, 6000]。 */
    private static int clampTrailTicks(Integer value) {
        if (value == null) {
            return -1;
        }
        return Math.max(0, Math.min(value, 6000));
    }

    /** 尾迹是否使用本项目 MCHR 风格翻滚烟团（{@code rvp_smoke}）。 */
    public boolean isMissileNativeTrailRvpSmoke() {
        return "rvp_smoke".equals(getMissileNativeTrailParticleStyle());
    }

    /** 尾迹是否使用 HBM 风格火箭尾焰（{@code rvp_rocket_flame}，液氧煤油黑烟技术储备款）。 */
    public boolean isMissileNativeTrailRocketFlame() {
        return "rvp_rocket_flame".equals(getMissileNativeTrailParticleStyle());
    }

    /** 尾迹是否使用液氧煤油黑烟款（{@code rvp_kerosene_black_smoke}，技术储备：09-19 前的原始黑烟观感）。 */
    public boolean isMissileNativeTrailKeroseneBlackSmoke() {
        return "rvp_kerosene_black_smoke".equals(getMissileNativeTrailParticleStyle());
    }

    /** 是否配置了任一自定义尾迹风格（非空即真）；服务端据此跳过原生尾迹弹的广播路径。 */
    public boolean hasMissileNativeTrailParticleStyle() {
        return !getMissileNativeTrailParticleStyle().isEmpty();
    }

    /**
     * 发射段烟柱加粗倍率；未配置或非法值返回 {@code 1.0}（关闭）。
     * 下限 0；语义见字段注释——在一级燃烧窗口内随飞行进度线性回落到 1.0。
     */
    public float getMissileNativeTrailLaunchBoost() {
        if (missileNativeTrailLaunchBoost == null
                || Float.isNaN(missileNativeTrailLaunchBoost)
                || Float.isInfinite(missileNativeTrailLaunchBoost)) {
            return 1f;
        }
        return Math.max(missileNativeTrailLaunchBoost, 0f);
    }

    /**
     * 发射段贴地烟浪是否启用；未配置时按风格推导——
     * {@code rvp_rocket_flame} / {@code rvp_kerosene_black_smoke} 开启（HBM 观感打包），其余风格默认关闭。
     */
    public boolean isMissileNativeTrailGroundWashEnabled() {
        if (missileNativeTrailGroundWash != null) {
            return missileNativeTrailGroundWash;
        }
        return isMissileNativeTrailRocketFlame() || isMissileNativeTrailKeroseneBlackSmoke();
    }

    public boolean isMissileNativeTrailExtraFlameEnabled() {
        return missileNativeTrailExtraFlame == null || missileNativeTrailExtraFlame;
    }

    public boolean isMissileNativeTrailExtraSmokeEnabled() {
        return missileNativeTrailExtraSmoke == null || missileNativeTrailExtraSmoke;
    }

    public String getImpactParticle() {
        return impactParticle == null ? "" : impactParticle;
    }

    public String getExplosionParticle() {
        return explosionParticle == null ? "" : explosionParticle;
    }

    public int getFlakParticlesCrack() {
        return Math.max(flakParticlesCrack, 0);
    }

    public int getNumParticlesFlak() {
        return Math.max(numParticlesFlak, 0);
    }

    public float getFlakParticlesDiff() {
        return Math.max(flakParticlesDiff, 0f);
    }

    public boolean hasCaliberOverride() {
        return caliber != null;
    }

    public float getCaliber() {
        return caliber != null && caliber > 0f ? caliber : 7.62f;
    }

    /** 是否显式配置了曳光口径覆盖（effects_data.tracer_caliber）。 */
    public boolean hasTracerCaliberOverride() {
        return tracerCaliber != null;
    }

    /** 曳光口径/弹体视觉口径：优先 tracer_caliber，其次 caliber，最后 7.62 mm。 */
    public float getTracerCaliber() {
        if (tracerCaliber != null && tracerCaliber > 0f) {
            return tracerCaliber;
        }
        return getCaliber();
    }

    public boolean hasTracerColorOverride() {
        return tracerR != null || tracerG != null || tracerB != null;
    }

    public float getTracerR() {
        return tracerR != null ? tracerR : 1f;
    }

    public float getTracerG() {
        return tracerG != null ? tracerG : 0.85f;
    }

    public float getTracerB() {
        return tracerB != null ? tracerB : 0.2f;
    }

    /** @return 是否显式配置了 tracer_length_scale（配置探测预留查询，渲染链路直接用 getTracerLengthScale）。 */
    public boolean hasTracerLengthScaleOverride() {
        return tracerLengthScale != null;
    }

    /** 曳光长度倍率；未配置或非法返回 1.0，下限 0、上限 8。 */
    public float getTracerLengthScale() {
        if (tracerLengthScale == null
                || Float.isNaN(tracerLengthScale)
                || Float.isInfinite(tracerLengthScale)) {
            return 1f;
        }
        return Math.max(0f, Math.min(tracerLengthScale, 8f));
    }

    /**
     * 机枪曳光长度（格）：min(0.3 × 弹速 × tracer_length_scale, 已飞距离 × 0.8)。
     * 倍率默认 1.0 = 历史行为。纯数学静态方法（本类无 MC 依赖，供渲染端与单测共用；
     * 不放渲染类是因为其类图引用动画库，测试运行时类路径加载不到）。
     */
    public static double resolveTracerLength(double speed, double travelDistance, float scale) {
        double len = 0.3 * speed * scale;
        return Math.min(len, travelDistance * 0.8);
    }

    /** 线导视觉线开关（effects_data.wire_link_enabled）。 */
    public boolean isWireLinkEnabled() {
        return wireLinkEnabled != null && wireLinkEnabled;
    }

    public RVP_ParticleProjectileData getParticleProjectileData() {
        return particleProjectileData == null ? new RVP_ParticleProjectileData() : particleProjectileData;
    }

    /** 命中瞬间补渲轨迹粒子开关（effects_data.impact_trail_particles）。 */
    public boolean isImpactTrailParticles() {
        return impactTrailParticles != null && impactTrailParticles;
    }

    /** 穿透丢弃路径专用补渲开关（effects_data.penetrate_impact_trail_particles），缺省关。 */
    public boolean isPenetrateImpactTrailParticles() {
        return penetrateImpactTrailParticles != null && penetrateImpactTrailParticles;
    }

    public boolean isImpactDisabled() {
        return isNone(getImpactParticle());
    }

    public boolean isExplosionDisabled() {
        return isNone(getExplosionParticle());
    }

    public boolean isDefaultBlockImpact() {
        String id = getImpactParticle();
        return id.isBlank()
                || "block".equalsIgnoreCase(id)
                || "minecraft:block".equalsIgnoreCase(id);
    }

    public boolean isDefaultVanillaExplosion() {
        String id = getExplosionParticle();
        return id.isBlank()
                || "explosion".equalsIgnoreCase(id)
                || "minecraft:explosion".equalsIgnoreCase(id)
                || "explosion_emitter".equalsIgnoreCase(id)
                || "minecraft:explosion_emitter".equalsIgnoreCase(id);
    }

    private static boolean isNone(String id) {
        return "none".equalsIgnoreCase(id) || "minecraft:none".equalsIgnoreCase(id);
    }
}
