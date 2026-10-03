package org.ywzj.rvp.weapon.gps;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSState.Mode;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * [RVP] GPS 目标模式的支持性解析（2026-10-02，模式参数化）：模式列表来自武器 JSON
 * {@code guidance_data.gps_modes}（见 {@link RVP_GuidanceDataGPS}），FAST/RADAR 另有
 * 传感器门禁——同一武器站传感器单选，eo_ccip 与 RF 互斥，故"最多配置 4 种、最多生效 3 种"。
 *
 * <ul>
 *   <li>SINGLE/MULTI：恒可用（默认配置，存量武器行为不变）；</li>
 *   <li>FAST：CCIP 系传感器——武器数据 {@code rvp_fire_control_sensor_mode == "eo_ccip"}
 *       动态模式，或有效传感器为静态 {@code ccip}（2026-10-03 放宽，任一即可）；</li>
 *   <li>RADAR：{@code RVP_WeaponSensorHelper.effectiveOrStatic(unit) == RF}——CCIP 系传感器
 *       （静态 ccip / eo_ccip）均不解析为 RF，永不与 RADAR 同时可用。</li>
 * </ul>
 *
 * <p>双端可用：客户端用于模式循环切换与 UI，服务端用于 {@code C2SSetGPSTarget.SET_MODE}
 * 校验（非法模式忽略并回发快照）。</p>
 */
public final class RVP_GpsModeSupport {

    private RVP_GpsModeSupport() {}

    /** 当前武器实际可用的模式序列（配置列表 ∩ 传感器门禁，保配置顺序）。 */
    public static List<Mode> availableModes(@Nullable WeaponUnit unit, @Nullable RVP_WeaponData weaponData) {
        List<Mode> configured = RVP_GuidanceDataGPS.resolveConfiguredModes(
                weaponData == null ? null : weaponData.getGuidanceData());
        List<Mode> out = new ArrayList<>();
        for (Mode mode : configured) {
            if (isModeAllowed(unit, weaponData, mode)) {
                out.add(mode);
            }
        }
        return out.isEmpty() ? List.of(Mode.SINGLE) : List.copyOf(out);
    }

    /** 单个模式的传感器门禁判定。 */
    public static boolean isModeAllowed(@Nullable WeaponUnit unit,
                                        @Nullable RVP_WeaponData weaponData,
                                        Mode mode) {
        return switch (mode) {
            case SINGLE, MULTI -> true;
            // FAST 要求 CCIP 系传感器（2026-10-03 放宽）：eo_ccip 动态模式，或有效传感器为
            // 静态 ccip——任一即可；effectiveOrStatic 双端一致且不随开镜视角闪断
            case FAST -> weaponData != null && (
                    "eo_ccip".equalsIgnoreCase(weaponData.getFireControlSensorMode())
                    || (unit != null && RVP_WeaponSensorHelper.effectiveOrStatic(unit, weaponData)
                            == WeaponUnitData.FireControlSensorType.CCIP));
            // RADAR 要求 RF 传感器（eo_ccip 动态模式只出 EO/CCIP，天然互斥）
            case RADAR -> unit != null && RVP_WeaponSensorHelper.effectiveOrStatic(unit)
                    == WeaponUnitData.FireControlSensorType.RF;
        };
    }

    /** 指定模式是否可用（服务端 SET_MODE 校验用）。 */
    public static boolean isAvailable(@Nullable WeaponUnit unit,
                                      @Nullable RVP_WeaponData weaponData,
                                      Mode mode) {
        return availableModes(unit, weaponData).contains(mode);
    }

    /** 循环切换：从 current 起取可用序列的下一个；current 不在序列（配置变更）则回首个可用。 */
    public static Mode nextAvailable(@Nullable WeaponUnit unit,
                                     @Nullable RVP_WeaponData weaponData,
                                     Mode current) {
        List<Mode> available = availableModes(unit, weaponData);
        int index = available.indexOf(current);
        if (index < 0) {
            return available.get(0);
        }
        return available.get((index + 1) % available.size());
    }

    /** MULTI 目标点上限（当前武器配置，缺省 8）。 */
    public static int multiMaxPoints(@Nullable RVP_WeaponData weaponData) {
        return RVP_GuidanceDataGPS.resolveMultiMaxPoints(
                weaponData == null ? null : weaponData.getGuidanceData());
    }

    /** RADAR 在途改靶间隔（秒，当前武器配置，缺省 5）。 */
    public static float radarUpdateIntervalSecond(@Nullable RVP_WeaponData weaponData) {
        return RVP_GuidanceDataGPS.resolveRadarUpdateIntervalSecond(
                weaponData == null ? null : weaponData.getGuidanceData());
    }

    /**
     * 解析操作者当前武器站上选中的 RVP 武器数据；非 RVP 武器/无选中返回 null
     * （此时仅 SINGLE/MULTI 可用，其余字段走默认）。
     */
    @Nullable
    public static RVP_WeaponData currentRvpWeaponData(@Nullable WeaponUnit unit) {
        if (unit == null) {
            return null;
        }
        return unit.getCurrentWeapon()
                .filter(weapon -> weapon instanceof org.ywzj.rvp.weapon.core.RVP_WeaponBase)
                .map(weapon -> ((org.ywzj.rvp.weapon.core.RVP_WeaponBase) weapon).getData())
                .orElse(null);
    }

    /**
     * 操作者（玩家/gunner）当前操作的武器站；骑乘载具时为 {@code vehicle.getOwnOperatorUnit}
     * 的 WeaponUnit 视图，与本体开火分发同源。
     */
    @Nullable
    public static WeaponUnit operatedWeaponUnit(LivingEntity operator) {
        if (operator == null || !(operator.getVehicle() instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle)) {
            return null;
        }
        return vehicle.getOwnOperatorUnit(operator) instanceof WeaponUnit weaponUnit ? weaponUnit : null;
    }
}
