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
