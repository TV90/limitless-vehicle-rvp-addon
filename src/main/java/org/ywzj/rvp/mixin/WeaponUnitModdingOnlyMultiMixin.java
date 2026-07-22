package org.ywzj.rvp.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitModdingOnlyMultiMixin {

    @Inject(method = "cycleMultiWeapon", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$blockRuntimeMultiCycle(boolean next, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        int weaponIndex = self.getCurrentWeaponIndex();
        if (!RVP_VehicleExtendedConfigManager.INSTANCE.shouldBlockRuntimeMultiCycle(self, weaponIndex)) {
            return;
        }
        if (self.getOwner() instanceof Player player) {
            player.displayClientMessage(Component.literal("该武器变体只能在改装工具中切换"), true);
        }
        ci.cancel();
    }
}
