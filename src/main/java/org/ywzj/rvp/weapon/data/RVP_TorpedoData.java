package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * [RVP] 鱼雷数据模型（2026-10-02，{@code rvp:torpedo} 公开类型专用）。
 *
 * <p>JSON 键 {@code torpedo_data}，仅 {@code type: "rvp:torpedo"} 武器解析。鱼雷
 * 出管段复用 {@link RVP_ProjectileData} 弹道（初速/重力/阻力/继承载具速度），有效入水后
 * 由本数据接管水中动力学：定速直航（独立于 {@code constant_speed} 峰值回填体系）+
 * 垂直速度衰减被动摆平（深度保持）。运行时状态机见
 * {@link org.ywzj.rvp.entity.projectile.RVP_TorpedoEntity}。</p>
 *
 * <p>设计文档：{@code docs/plan/RVP鱼雷武器实现方案_20261002.md}；
 * 字段手册：{@code docs/plan/RVP武器数据模型/RVP 鱼雷武器数据模型文档.md}。</p>
 */
public class RVP_TorpedoData {

    /**
     * 水中巡航速度（格/tick，20 tick = 1 秒，1 格 = 1 米）：有效入水过渡完成后维持的
     * 恒定速率，即鱼雷的真实航速指标（默认 1.5 ≈ 30 米/秒，接近真实重型鱼雷）。
     * 水中速率完全由此值绑定，不受空气段 {@code max_speed}/{@code constant_speed} 影响。
     * 配置 ≤0 时按默认值 1.5 处理（防呆：0 会让鱼雷在水中冻结）。
     */
    @SerializedName("water_speed")
    private float waterSpeed = 1.5f;

    /**
     * 入水过渡时长（tick）：从入水瞬间的实际速率线性过渡到 {@link #waterSpeed}
     * （模拟入水减速与螺旋桨起转），过渡期间方向保持不变。0 = 入水立即定速；
     * 配置负值按 0 处理。
     */
    @SerializedName("water_entry_lerp_tick")
    private int waterEntryLerpTick = 10;

    /**
     * 水中垂直速度每 tick 保留比例：每 tick 对 Y 分量乘该值后重新归一化到巡航速率，
     * 鱼雷俯仰自然摆平到水平直航（被动深度保持）。{@code 1.0} = 不衰减（俯仰保持入水角，
     * 可配合 {@code projectile_data.gravity_in_water} 模拟下沉/上浮弧线）；有效域 [0,1]，
     * 越界钳制（负值按 0 = 立即压平处理）。
     */
    @SerializedName("depth_damping")
    private float depthDamping = 0.8f;

    /**
     * 有效入水深度（格）：实体中心上方该距离处仍为水方块才判定"完全入水"——防止贴水皮
     * 反复切换状态（浪区/水面掠飞抖动）。入水/出水的状态切换均以该判定为准；
     * 配置负值按默认值 0.5 处理。
     */
    @SerializedName("water_entry_min_depth")
    private float waterEntryMinDepth = 0.5f;

    /**
     * 发射门：{@code true} 时发射瞬间服务端沿武器站瞄准方向做射线检测，命中流体方块
     * 才允许发射，否则拒绝且不耗弹并向射手提示 {@code ui.rvp.torpedo_not_aiming_water}
     * （"未瞄准水面"）——防止瞄着陆地/舰桥误发。默认 {@code false}（飞机空投、甲板
     * 直射不受限）。
     */
    @SerializedName("require_aim_water")
    private boolean requireAimWater = false;

    /**
     * {@link #requireAimWater} 的瞄准线检测最大距离（格），该距离内命中流体方块即放行；
     * 配置 ≤0 时按默认值 150 处理。
     */
    @SerializedName("require_aim_water_range")
    private float requireAimWaterRange = 150f;

    /** 巡航速度（防呆钳制：≤0 回默认）。 */
    public float getWaterSpeed() {
        return waterSpeed > 0f ? waterSpeed : 1.5f;
    }

    /** 入水过渡时长（负值归 0，0 = 立即定速）。 */
    public int getWaterEntryLerpTick() {
        return Math.max(0, waterEntryLerpTick);
    }

    /** 垂直速度保留比（钳制 [0,1]）。 */
    public float getDepthDamping() {
        return Math.max(0f, Math.min(1f, depthDamping));
    }

    /** 有效入水深度（负值回默认 0.5）。 */
    public float getWaterEntryMinDepth() {
        return waterEntryMinDepth >= 0f ? waterEntryMinDepth : 0.5f;
    }

    /** 是否启用瞄水发射门。 */
    public boolean isRequireAimWater() {
        return requireAimWater;
    }

    /** 瞄水检测距离（≤0 回默认 150）。 */
    public float getRequireAimWaterRange() {
        return requireAimWaterRange > 0f ? requireAimWaterRange : 150f;
    }
}
