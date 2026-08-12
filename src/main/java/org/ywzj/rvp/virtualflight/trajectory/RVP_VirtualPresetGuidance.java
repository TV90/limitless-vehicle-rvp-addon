package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * 弹道导弹（PRESET 三段式）的虚拟中段纯输入。
 *
 * <p>参考点（上升段终点/巡航段头顶点）由积分器在每 Tick 从发射点与固定目标推导，
 * 与实体端 {@code RVP_BaseBullet.initializePresetProfile} 使用同一套公式，保证恢复实体后弹道连续。</p>
 *
 * @param launchPosition 发射点（虚拟记录 {@code launchPosition}，与实体 {@code virtualMidcourseLaunchPosition} 一致）
 * @param cruiseAltitude 巡航高度（相对发射点Y）
 * @param maxAscentLead 上升段前伸量上限
 * @param ascentRadius 上升段完成判定半径
 * @param diveRadius 俯冲段最小启动水平距离
 * @param diveAltitudeFactor 俯冲距离 = 高度差 × 因子
 * @param diveLeadFactor 俯冲距离 = 近似转弯半径 × 因子
 * @param cruiseAltitudeGain 高度闭环 P 增益
 * @param cruiseVerticalDamping 高度闭环 D 阻尼
 * @param cruiseMaxVerticalComponent 垂直分量占速率比例上限
 * @param tacticalManeuverAmplitude 弹道中段战术机动（横向蛇形规避摆动）幅度，格；0 = 关闭
 */
public record RVP_VirtualPresetGuidance(
        Vec3 launchPosition,
        double cruiseAltitude,
        double maxAscentLead,
        double ascentRadius,
        double diveRadius,
        double diveAltitudeFactor,
        double diveLeadFactor,
        double cruiseAltitudeGain,
        double cruiseVerticalDamping,
        double cruiseMaxVerticalComponent,
        double tacticalManeuverAmplitude
) {

    /** 从武器配置冻结弹道导弹输入；未启用 preset 时返回 null。 */
    public static RVP_VirtualPresetGuidance from(RVP_WeaponData data, Vec3 launchPosition) {
        if (data == null || data.getGuidanceData() == null
                || data.getGuidanceData().getPresetCruiseAltitude() <= 0f) {
            return null;
        }
        var guidance = data.getGuidanceData();
        return new RVP_VirtualPresetGuidance(
                launchPosition,
                guidance.getPresetCruiseAltitude(),
                guidance.getPresetMaxAscentLead(),
                guidance.getPresetAscentRadius(),
                guidance.getPresetDiveRadius(),
                guidance.getPresetDiveAltitudeFactor(),
                guidance.getPresetDiveLeadFactor(),
                guidance.getPresetCruiseAltitudeGain(),
                guidance.getPresetCruiseVerticalDamping(),
                guidance.getPresetCruiseMaxVerticalComponent(),
                guidance.getPresetTacticalManeuverAmplitude()
        );
    }
}
