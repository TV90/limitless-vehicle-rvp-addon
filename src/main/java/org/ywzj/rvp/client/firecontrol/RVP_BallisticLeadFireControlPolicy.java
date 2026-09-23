package org.ywzj.rvp.client.firecontrol;

import org.ywzj.vehicle.custom.part.data.WeaponUnitData;

/**
 * 通用弹道提前量火控的模式解析策略。
 *
 * <p>传感器只负责提供并维持锁定目标，火控模式负责决定是否使用弹道提前量驱动武器站。
 * 现有 {@code rvp_rf} 仍严格要求 RF；新的 {@code rvp_ballistic_lead} 可复用本体支持锁定的
 * IR、EO、RF 传感器。</p>
 */
public final class RVP_BallisticLeadFireControlPolicy {
    /** 现有 RF 软火控模式名，保留当前载具包配置兼容性。 */
    public static final String MODE_RVP_RF = "rvp_rf";

    /** 与传感器类型解耦的通用弹道提前量火控模式名。 */
    public static final String MODE_BALLISTIC_LEAD = "rvp_ballistic_lead";

    /** 已解析的火控执行配置。 */
    public enum Profile {
        /** 未配置受支持的RVP火控，调用方应回退本体逻辑。 */
        NONE,
        /** 现有 RF 软火控，允许非机炮继续使用目标中心软跟踪。 */
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
     * 判断当前配置是否允许机炮切换 STABLE、SEMI_AUTO、OFF 三态。
     */
    public static boolean supportsStabilizer(String configuredMode,
                                             WeaponUnitData.FireControlSensorType sensorType,
                                             boolean rvpMachinegun) {
        if (!rvpMachinegun) {
            return false;
        }
        return resolve(configuredMode, sensorType, true) != Profile.NONE;
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
