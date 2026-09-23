package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.firecontrol.RVP_BallisticLeadFireControlPolicy;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.core.RVP_WeaponSensorHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class RVP_FireControlStabilizerState {
    public enum Mode {
        STABLE,
        SEMI_AUTO,
        OFF;

        public Mode next() {
            return switch (this) {
                case STABLE -> OFF;
                case SEMI_AUTO -> STABLE;
                case OFF -> SEMI_AUTO;
            };
        }
    }

    private static final java.util.Map<WeaponUnit, Mode> MODES = new WeakHashMap<>();

    private RVP_FireControlStabilizerState() {}

    public static Mode getMode(@Nullable WeaponUnit unit) {
        if (unit == null || !isEligible(unit)) {
            return Mode.SEMI_AUTO;
        }
        return MODES.getOrDefault(unit, Mode.SEMI_AUTO);
    }

    /** 火控稳定器键按下时切换稳定模式。由按键消费方保证是 FIRE_CONTROL_STABILIZER 键。 */
    public static boolean tryHandleToggleKey(@Nullable WeaponUnit unit) {
        if (unit == null || !isEligible(unit)) {
            return false;
        }
        Mode mode = getMode(unit).next();
        MODES.put(unit, mode);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable(switch (mode) {
                        case STABLE -> "message.ywzj_rvp.fire_control_mode.stable";
                        case SEMI_AUTO -> "message.ywzj_rvp.fire_control_mode.semi_auto";
                        case OFF -> "message.ywzj_rvp.fire_control_mode.off";
                    }),
                    true
            );
        }
        return true;
    }

    private static boolean isEligible(WeaponUnit unit) {
        // 调用本项目武器解析器，解包Agent/Multi后确认当前实际武器是否为RVP机炮。
        RVP_WeaponBase weapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        boolean rvpMachinegun = weapon != null
                && weapon.getData().getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN;
        if (!rvpMachinegun) {
            return false;
        }
        if (!(unit.getData() instanceof WeaponUnitDataExt ext)) {
            return false;
        }
        // 调用本项目传感器解析器，使武器级动态覆盖与三态切换资格保持一致。
        WeaponUnitData.FireControlSensorType sensorType = RVP_WeaponSensorHelper.effectiveSensorType(unit);
        return RVP_BallisticLeadFireControlPolicy.supportsStabilizer(
                ext.ywzj_rvp$getFireControlMode(),
                sensorType,
                true
        );
    }
}
