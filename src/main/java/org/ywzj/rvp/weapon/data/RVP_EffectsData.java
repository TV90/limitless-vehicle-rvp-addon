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

    /** 导弹尾迹沿路径采样间距，单位格，默认 0.5；启用原生尾迹时最小为 0.05。 */
    @SerializedName("missile_native_trail_step")
    private Float missileNativeTrailStep;

    /** 导弹尾迹生成间隔，单位 Tick，默认 1；启用原生尾迹时至少为 1。 */
    @SerializedName("missile_native_trail_spawn_interval_tick")
    private Integer missileNativeTrailSpawnIntervalTick;

    /** 导弹尾迹采样密度倍率，默认 1；启用原生尾迹时限制为非负有限值。 */
    @SerializedName("missile_native_trail_density_scale")
    private Float missileNativeTrailDensityScale;

    /** 导弹尾迹相对弹体尾部偏移，单位格，默认 3；启用原生尾迹时限制为非负。 */
    @SerializedName("missile_native_trail_offset")
    private Float missileNativeTrailOffset;

    /**
     * 导弹尾迹粒子渲染尺寸倍率，默认 {@code 1}（原尺寸）。
     *
     * <p>与 {@code missile_native_trail_density_scale}（只增加粒子<b>数量</b>）互补：本项放大
     * 单个粒子的<b>渲染尺寸</b>，是让尾迹"变粗"的直接手段。仅客户端本地尾迹生效
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
     *   <li>{@code rvp_rocket_flame}：HBM 风格火箭尾焰·固体发动机凝结云款（`RVP_RocketFlameParticle`，
     *       从 HBM 的 `ParticleRocketFlame` 移植）——寿命前 25% 亮橙火焰团、烟相位出生中灰随寿命
     *       smoothstep 渐变凝结云灰白，寿命延长（120~180t）+ 前段保持 + 距离 LOD；默认启用发射段
     *       贴地烟浪（见 {@code missile_native_trail_ground_wash}）；</li>
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
     * {@code 20} 格时，客户端在弹体地面投影点生成贴地横向冲刷的灰烟团（每 tick 6 粒，
     * 尺寸 0.25 → 2.25 × {@code missile_native_trail_particle_scale} 线性膨胀带浮升）。</p>
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

    /** 命中粒子短名或资源 ID，默认空（使用方块命中默认表现）；写 {@code none} 时关闭。 */
    @SerializedName("impact_particle")
    private String impactParticle = "";

    /** 爆炸附加粒子短名或资源 ID，默认空（使用原版爆炸粒子）；写 {@code none} 时关闭。 */
    @SerializedName("explosion_particle")
    private String explosionParticle = "";

    /** 弹体命中瞬间即死亡（飞行时间过短、从未广播过轨迹粒子）时，在命中点补渲轨迹粒子簇。 */
    @SerializedName("impact_trail_particles")
    private Boolean impactTrailParticles;

    /** 方块破碎粒子基数，单位个，默认 10；非负值仅在默认方块命中粒子模式生效。 */
    @SerializedName("flak_particles_crack")
    private int flakParticlesCrack = 10;

    /** 命中白烟粒子数量，单位个，默认 3；非负值仅在默认方块命中粒子模式生效。 */
    @SerializedName("num_particles_flak")
    private int numParticlesFlak = 3;

    /** 方块破碎粒子速度散布，单位格/Tick，默认 0.3；默认方块命中粒子模式生效。 */
    @SerializedName("flak_particles_diff")
    private float flakParticlesDiff = 0.3f;

    /** 机枪弹口径覆盖，单位毫米，默认 null；未配置或非正值时按 7.62。 */
    @SerializedName("caliber")
    private Float caliber;

    /** 机枪曳光红色通道，范围通常 0～1，默认 null（按 1）；仅机枪 Renderer 生效。 */
    @SerializedName("tracer_r")
    private Float tracerR;

    /** 机枪曳光绿色通道，范围通常 0～1，默认 null（按 0.85）；仅机枪 Renderer 生效。 */
    @SerializedName("tracer_g")
    private Float tracerG;

    /** 机枪曳光蓝色通道，范围通常 0～1，默认 null（按 0.2）；仅机枪 Renderer 生效。 */
    @SerializedName("tracer_b")
    private Float tracerB;

    /** 线导视觉线：导弹与发射枢轴间绘制原版钓鱼线风格线缆。 */
    @SerializedName("wire_link_enabled")
    private Boolean wireLinkEnabled;

    /** 纯粒子弹体视觉配置，默认禁用；启用时类型化 Renderer 跳过模型并由客户端 Tick 生成粒子。 */
    @SerializedName("particle_projectile_data")
    private RVP_ParticleProjectileData particleProjectileData = new RVP_ParticleProjectileData();

    public String getTrajectoryParticle() {
        return trajectoryParticle == null ? "" : trajectoryParticle;
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
