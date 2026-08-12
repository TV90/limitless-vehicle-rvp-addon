package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 单条 {@code visual_effect_data} 的实验性行为配置。
 * <p>
 * 实验字段默认关闭；未显式启用时不得改变既有视觉行为。
 */
public final class RVP_VisualEffectExperimentalData {

    /**
     * 是否启用动态粒子预算；默认 {@code false}。仅由支持该实验能力的视觉类型生效，
     * 当前仅 {@code rvp:thermobaric} 使用；启用后粒子数在对应 full tick 前按几何覆盖量增长，
     * 且始终受既有 {@code max_*}、视觉密度和距离 LOD 约束。
     */
    @SerializedName("dynamic_particle_budget")
    private boolean dynamicParticleBudget = false;

    /** 返回是否显式启用了动态粒子预算实验。 */
    public boolean isDynamicParticleBudget() {
        return dynamicParticleBudget;
    }
}
