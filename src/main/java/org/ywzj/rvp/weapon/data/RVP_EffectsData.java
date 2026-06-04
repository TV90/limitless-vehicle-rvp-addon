package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 弹体生命周期特效参数（粒子为主）。
 *
 * <p>命中默认（{@code impact_particle} 为空或 {@code minecraft:block}）对应 MCH
 * {@code spawnBlockPar}：方块破碎粒子 + 白烟（{@code CLOUD}），散布由 {@code flak_particles_*} 控制。</p>
 *
 * <p>爆炸默认（{@code explosion_particle} 为空或 {@code minecraft:explosion*}）为原版
 * {@code EXPLOSION_EMITTER} + {@code EXPLOSION}；载具 {@link org.ywzj.vehicle.util.VehicleExplosion}
 * 仍会下发客户端音效与烟雾。</p>
 */
public class RVP_EffectsData {

    /** 飞行轨迹粒子；写 {@code none} 可关闭。 */
    @SerializedName("trajectory_particle")
    private String trajectoryParticle = "minecraft:cloud";

    /**
     * 命中粒子。空 / {@code minecraft:block} = 方块破碎 + 白烟（MCH 默认）。
     * {@code none} = 不生成。
     */
    @SerializedName("impact_particle")
    private String impactParticle = "";

    /**
     * 爆炸粒子。空 / {@code minecraft:explosion*} = 原版爆炸粒子。
     * {@code none} = 不额外生成（爆炸伤害仍由 {@code explosion} 字段决定）。
     */
    @SerializedName("explosion_particle")
    private String explosionParticle = "";

    /** MCH {@code FlakParticlesCrack}：方块破碎粒子数量基数（实际 +0~2）。默认 10。 */
    @SerializedName("flak_particles_crack")
    private int flakParticlesCrack = 10;

    /** MCH {@code NumParticlesFlak}：白烟（CLOUD）数量。默认 3。 */
    @SerializedName("num_particles_flak")
    private int numParticlesFlak = 3;

    /** MCH {@code FlakParticlesDiff}：破碎粒子速度散布，推荐 0.1（步枪）~ 0.6（反坦克）。默认 0.3。 */
    @SerializedName("flak_particles_diff")
    private float flakParticlesDiff = 0.3f;

    /**
     * 机枪曳光弹口径（毫米），影响客户端曳光条宽度与弹孔粒子大小。
     * 仅 {@code rvp:machinegun} 弹体使用。
     */
    @SerializedName("caliber")
    private Float caliber;

    /** 机枪曳光 RGB 红分量，0–1；默认约 {@code 1.0}。 */
    @SerializedName("tracer_r")
    private Float tracerR;

    /** 机枪曳光 RGB 绿分量，0–1；默认约 {@code 0.85}。 */
    @SerializedName("tracer_g")
    private Float tracerG;

    /** 机枪曳光 RGB 蓝分量，0–1；默认约 {@code 0.2}。 */
    @SerializedName("tracer_b")
    private Float tracerB;

    public String getTrajectoryParticle() {
        return trajectoryParticle == null ? "" : trajectoryParticle;
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

    public float getCaliber() {
        return caliber != null && caliber > 0f ? caliber : 7.62f;
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

    public boolean isImpactDisabled() {
        return isNone(getImpactParticle());
    }

    public boolean isExplosionDisabled() {
        return isNone(getExplosionParticle());
    }

    /** 空或 {@code block}：方块材质破碎粒子 + 白烟。 */
    public boolean isDefaultBlockImpact() {
        String id = getImpactParticle();
        return id.isBlank()
                || "block".equalsIgnoreCase(id)
                || "minecraft:block".equalsIgnoreCase(id);
    }

    /** 空或原版爆炸 id：{@code EXPLOSION_EMITTER} + 若干 {@code EXPLOSION}。 */
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
