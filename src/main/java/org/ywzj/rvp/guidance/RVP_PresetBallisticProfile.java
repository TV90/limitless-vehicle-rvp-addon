package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryPlan;

/**
 * 弹道导弹（PRESET 三段式：上升→巡航→俯冲）的运行时弹道剖面。
 *
 * <p>字段来自 {@code RVP_GuidanceData} 平铺的 {@code preset_*} 配置，命名与默认值对齐本体
 * {@code VehicleMissileWeaponData}。{@code cruiseAltitude > 0} 时激活，仅对 GPS 制导生效。</p>
 *
 * @param cruiseAltitude 相对起终点高度连线的中段抬升上限，单位格
 * @param maxAscentLead 前视距离基准，攻角模式至少覆盖响应路程，再受剩余水平距离的 30% 约束
 * @param ascentRadius 上升段完成判定半径
 * @param diveRadius 俯冲段最小启动水平距离
 * @param diveAltitudeFactor 俯冲距离 = 高度差 × 因子
 * @param diveLeadFactor 转弯提前量系数，攻角模式在近似转弯半径乘本值之后另加响应路程
 * @param cruiseAltitudeGain 高度闭环 P 增益
 * @param cruiseVerticalDamping 高度闭环 D 阻尼
 * @param cruiseMaxVerticalComponent 垂直分量占速率比例上限
 * @param tacticalManeuverAmplitude 弹道中段战术机动（横向蛇形规避摆动）幅度，格；0 = 关闭
 */
public record RVP_PresetBallisticProfile(
        float cruiseAltitude,
        float maxAscentLead,
        float ascentRadius,
        float diveRadius,
        float diveAltitudeFactor,
        float diveLeadFactor,
        float cruiseAltitudeGain,
        float cruiseVerticalDamping,
        float cruiseMaxVerticalComponent,
        float tacticalManeuverAmplitude
) {

    /** 把实体配置投影为共用数学参数，保证实体和虚拟态使用同一条弹道。 */
    public RVP_BallisticTrajectoryPlan at(Vec3 launch) {
        return new RVP_BallisticTrajectoryPlan(launch, cruiseAltitude, maxAscentLead,
                diveRadius, diveAltitudeFactor, diveLeadFactor, tacticalManeuverAmplitude);
    }

    /** @return 是否启用弹道导弹三段式弹道。 */
    public boolean active() {
        return cruiseAltitude > 0f;
    }

    /** @return 未激活的默认剖面（不启用弹道导弹）。 */
    public static RVP_PresetBallisticProfile inactive() {
        return new RVP_PresetBallisticProfile(0f, 64f, 24f, 24f, 0.75f, 1.5f, 0.002f, 0.05f, 0.5f, 0f);
    }

    /** 从 {@code guidance_data} 平铺配置构造；空数据使用未激活默认剖面。 */
    public static RVP_PresetBallisticProfile of(RVP_GuidanceData data) {
        if (data == null) {
            return inactive();
        }
        return new RVP_PresetBallisticProfile(
                data.getPresetCruiseAltitude(),
                data.getPresetMaxAscentLead(),
                data.getPresetAscentRadius(),
                data.getPresetDiveRadius(),
                data.getPresetDiveAltitudeFactor(),
                data.getPresetDiveLeadFactor(),
                data.getPresetCruiseAltitudeGain(),
                data.getPresetCruiseVerticalDamping(),
                data.getPresetCruiseMaxVerticalComponent(),
                data.getPresetTacticalManeuverAmplitude()
        );
    }
}
