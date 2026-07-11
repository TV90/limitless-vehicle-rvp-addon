package org.ywzj.rvp.mixin;

import org.ywzj.rvp.config.WeaponBayManualOverrideManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.mixin.accessor.SwitchableUnitAccessor;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * 切换武器时自动开关对应弹舱。
 * 使用 tick 注入确保所有切换路径都生效。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitSetWeaponMixin {

    @Unique
    private int rvp$lastPrimaryWeaponIndex = -2;

    @Unique
    private int rvp$lastSecondaryWeaponIndex = -2;

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void rvp$onTick(CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (self.weaponBayUnits.isEmpty()) return;

        int curPrimaryIdx = self.getCurrentWeaponIndex();
        int curSecondaryIdx = self.getCurrentSecondaryWeaponIndex();
        if (WeaponBayManualOverrideManager.isOverrideActive(
                self.getVehicle().getId(), self.getIndex(), curPrimaryIdx, curSecondaryIdx)) {
            return;
        }

        if (curPrimaryIdx == rvp$lastPrimaryWeaponIndex && curSecondaryIdx == rvp$lastSecondaryWeaponIndex) {
            return;
        }

        rvp$lastPrimaryWeaponIndex = curPrimaryIdx;
        rvp$lastSecondaryWeaponIndex = curSecondaryIdx;

        // 找当前武器的弹舱
        WeaponBayUnit targetBay = rvp$resolveActiveWeaponBay(self, curPrimaryIdx, curSecondaryIdx);

        // 同步所有弹舱（直接字段赋值绕过 WeaponBayUnit 的独立 chat message）
        for (WeaponBayUnit bay : self.weaponBayUnits.values()) {
            boolean shouldBeOn = (bay == targetBay);
            if (((SwitchableUnitAccessor) bay).isOnField() != shouldBeOn) {
                ((SwitchableUnitAccessor) bay).setOnField(shouldBeOn);
            }
        }
    }

    @Unique
    private WeaponBayUnit rvp$resolveActiveWeaponBay(WeaponUnit self, int curPrimaryIdx, int curSecondaryIdx) {
        WeaponBayUnit targetBay = null;
        if (curPrimaryIdx >= 0 && curPrimaryIdx < self.weapons.size()) {
            targetBay = self.weaponBayUnits.get(self.weapons.get(curPrimaryIdx));
        }
        if (targetBay == null && curSecondaryIdx >= 0 && curSecondaryIdx < self.secondaryWeapons.size()) {
            targetBay = self.weaponBayUnits.get(self.secondaryWeapons.get(curSecondaryIdx));
        }
        return targetBay;
    }
}
