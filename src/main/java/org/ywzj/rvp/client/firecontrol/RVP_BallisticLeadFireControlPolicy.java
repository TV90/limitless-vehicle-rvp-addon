package org.ywzj.rvp.client.firecontrol;

import org.ywzj.vehicle.custom.part.data.WeaponUnitData;

/**
 * 通用弹道提前量火控的模式解析策略。
 *
 * <p>传感器只负责提供并维持锁定目标，火控模式负责决定是否驱动武器站。
 * {@code rvp_rf} 严格要求 RF，其RVP机炮使用弹道预瞄，RVP导弹使用雷达硬锁目标跟踪；
 * {@code rvp_ballistic_lead} 可为RVP机炮复用本体支持锁定的 IR、EO、RF 传感器。</p>
 */
public final class RVP_BallisticLeadFireControlPolicy {
    /** RF 火控模式名，兼容机炮预瞄、普通软修正与导弹硬锁跟踪。 */
    public static final String MODE_RVP_RF = "rvp_rf";

    /** 与传感器类型解耦的通用弹道提前量火控模式名。 */
    public static final String MODE_BALLISTIC_LEAD = "rvp_ballistic_lead";

    /** 已解析的火控执行配置。 */
    public enum Profile {
        /** 未配置受支持的RVP火控，调用方应回退本体逻辑。 */
        NONE,
        /** RF 火控，机炮、导弹和其他武器的具体执行策略由执行器再分流。 */
        RVP_RF,
        /** 通用弹道提前量火控，仅供RVP机炮使用。 */
        BALLISTIC_LEAD
    }

    /** 工具类不允许实例化。 */
    private RVP_BallisticLeadFireControlPolicy() {}

    /**
     * 按配置值、当前传感器与武器类型解析实际执行配置。
     *
     * @param configuredMode 武器站 {@code rvp_fire_control_mode} 原始值
     * @param sensorType 当前有效的本体火控传感器类型
     * @param rvpMachinegun 当前选中武器是否为RVP机炮
     * @return 可执行配置；条件不满足时返回 {@link Profile#NONE}
     */
    public static Profile resolve(String configuredMode,
                                  WeaponUnitData.FireControlSensorType sensorType,
                                  boolean rvpMachinegun) {
        String normalizedMode = configuredMode == null ? "" : configuredMode.trim();
        if (MODE_RVP_RF.equalsIgnoreCase(normalizedMode)) {
            return sensorType == WeaponUnitData.FireControlSensorType.RF ? Profile.RVP_RF : Profile.NONE;
        }
        if (MODE_BALLISTIC_LEAD.equalsIgnoreCase(normalizedMode)
                && rvpMachinegun
                && supportsTrackedTarget(sensorType)) {
            return Profile.BALLISTIC_LEAD;
        }
        return Profile.NONE;
    }

    /**
     * 判断当前配置是否允许RVP机炮或RF导弹切换完整三态。
     */
    public static boolean supportsStabilizer(String configuredMode,
                                             WeaponUnitData.FireControlSensorType sensorType,
                                             boolean rvpMachinegun,
                                             boolean rvpMissile) {
        if (rvpMachinegun) {
            return resolve(configuredMode, sensorType, true) != Profile.NONE;
        }
        return supportsRfMissileTrackTrim(configuredMode, sensorType, rvpMissile);
    }

    /**
     * 判断当前配置是否启用 {@code rvp_rf} 导弹的雷达目标跟踪与双轴微调。
     */
    public static boolean supportsRfMissileTrackTrim(String configuredMode,
                                                      WeaponUnitData.FireControlSensorType sensorType,
                                                      boolean rvpMissile) {
        String normalizedMode = configuredMode == null ? "" : configuredMode.trim();
        return rvpMissile
                && MODE_RVP_RF.equalsIgnoreCase(normalizedMode)
                && sensorType == WeaponUnitData.FireControlSensorType.RF;
    }

    /**
     * 判断传感器是否具备本体 {@code tickFireControl} 可维持的实体锁定链。
     */
    private static boolean supportsTrackedTarget(WeaponUnitData.FireControlSensorType sensorType) {
        return sensorType == WeaponUnitData.FireControlSensorType.IR
                || sensorType == WeaponUnitData.FireControlSensorType.EO
                || sensorType == WeaponUnitData.FireControlSensorType.RF;
    }
}
