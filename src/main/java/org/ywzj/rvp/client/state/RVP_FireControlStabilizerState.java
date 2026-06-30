package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
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

    public static boolean tryHandleToggleKey(@Nullable WeaponUnit unit, int key, int scanCode) {
        if (!RVP_Keys.FIRE_CONTROL_STABILIZER.matches(key, scanCode)) {
            return false;
        }
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
        if (unit.getCurrentWeapon().isEmpty()
                || !(unit.getCurrentWeapon().get() instanceof RVP_WeaponBase weapon)
                || weapon.getData().getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) {
            return false;
        }
        if (unit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            return false;
        }
        if (!(unit.getData() instanceof WeaponUnitDataExt ext)) {
            return false;
        }
        return "rvp_rf".equalsIgnoreCase(ext.ywzj_rvp$getFireControlMode());
    }
}
