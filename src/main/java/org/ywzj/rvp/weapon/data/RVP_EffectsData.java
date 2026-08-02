package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * Projectile lifecycle VFX parameters.
 */
public class RVP_EffectsData {

    @SerializedName("trajectory_particle")
    private String trajectoryParticle = "minecraft:cloud";

    @SerializedName("missile_native_trail_enabled")
    private Boolean missileNativeTrailEnabled;

    @SerializedName("missile_native_trail_particle")
    private String missileNativeTrailParticle = "";

    @SerializedName("missile_native_trail_step")
    private Float missileNativeTrailStep;

    @SerializedName("missile_native_trail_spawn_interval_tick")
    private Integer missileNativeTrailSpawnIntervalTick;

    @SerializedName("missile_native_trail_density_scale")
    private Float missileNativeTrailDensityScale;

    @SerializedName("missile_native_trail_offset")
    private Float missileNativeTrailOffset;

    @SerializedName("missile_native_trail_extra_flame")
    private Boolean missileNativeTrailExtraFlame;

    @SerializedName("missile_native_trail_extra_smoke")
    private Boolean missileNativeTrailExtraSmoke;

    @SerializedName("impact_particle")
    private String impactParticle = "";

    @SerializedName("explosion_particle")
    private String explosionParticle = "";

    @SerializedName("flak_particles_crack")
    private int flakParticlesCrack = 10;

    @SerializedName("num_particles_flak")
    private int numParticlesFlak = 3;

    @SerializedName("flak_particles_diff")
    private float flakParticlesDiff = 0.3f;

    @SerializedName("caliber")
    private Float caliber;

    @SerializedName("tracer_r")
    private Float tracerR;

    @SerializedName("tracer_g")
    private Float tracerG;

    @SerializedName("tracer_b")
    private Float tracerB;

    /** 线导视觉线：导弹与发射枢轴间绘制原版钓鱼线风格线缆。 */
    @SerializedName("wire_link_enabled")
    private Boolean wireLinkEnabled;

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
