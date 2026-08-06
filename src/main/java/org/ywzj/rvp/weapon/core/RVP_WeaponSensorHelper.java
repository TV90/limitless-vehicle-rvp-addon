package org.ywzj.rvp.weapon.core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 火控传感器类型覆盖的非 mixin 恢复（替代被删 {@code WeaponUnitSensorOverrideMixin}）。
 *
 * <p>本体 {@code WeaponUnit.getFireControlSensorType()} 返回的是挂架数据的静态值，
 * 无法覆写。原 mixin 在方法入口按当前 RVP 武器数据动态替换返回值
 * （{@code eo_ccip} 模式：SCOPE 视角返回 EO、其余返回 CCIP；或按
 * {@code fire_control_sensor_type_override} 覆盖）。本类把该解析移到 RVP 侧消费点：
 * <ul>
 *   <li>客户端射击门控 / HUD（准心、CCIP 落点、射界）→ {@link #effectiveSensorType}</li>
 *   <li>双端通用（服务端锁定分支等）→ {@link #effectiveOrStatic}</li>
 * </ul>
 * 非 RVP 武器一律回退到本体静态值，行为不变。
 */
public final class RVP_WeaponSensorHelper {

    private RVP_WeaponSensorHelper() {}

    /**
     * 客户端有效传感器类型：{@code eo_ccip} 按视角动态 EO/CCIP，override 覆盖，非 RVP 武器回退静态值。
     * 仅限客户端代码调用（引用 {@link LocalVehiclePlayer}）。
     */
    @OnlyIn(Dist.CLIENT)
    public static WeaponUnitData.FireControlSensorType effectiveSensorType(WeaponUnit unit) {
        if (unit == null) {
            return WeaponUnitData.FireControlSensorType.NONE;
        }
        RVP_WeaponBase weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        if (weapon == null) {
            return unit.getFireControlSensorType();
        }
        RVP_WeaponData data = weapon.getData();
        if ("eo_ccip".equalsIgnoreCase(data.getFireControlSensorMode())) {
            return LocalVehiclePlayer.instance != null
                    && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE
                    ? WeaponUnitData.FireControlSensorType.EO
                    : WeaponUnitData.FireControlSensorType.CCIP;
        }
        WeaponUnitData.FireControlSensorType override = data.getFireControlSensorTypeOverride();
        return override != null ? override : unit.getFireControlSensorType();
    }

    /**
     * 双端安全版本：仅数据层覆盖（{@code eo_ccip} 在服务端固定为 CCIP，
     * 与原 mixin 在服务端的行为一致），非 RVP 武器回退静态值。
     */
    public static WeaponUnitData.FireControlSensorType effectiveOrStatic(WeaponUnit unit) {
        if (unit == null) {
            return WeaponUnitData.FireControlSensorType.NONE;
        }
        RVP_WeaponBase weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        if (weapon == null) {
            return unit.getFireControlSensorType();
        }
        RVP_WeaponData data = weapon.getData();
        if ("eo_ccip".equalsIgnoreCase(data.getFireControlSensorMode())) {
            return WeaponUnitData.FireControlSensorType.CCIP;
        }
        WeaponUnitData.FireControlSensorType override = data.getFireControlSensorTypeOverride();
        return override != null ? override : unit.getFireControlSensorType();
    }
}
