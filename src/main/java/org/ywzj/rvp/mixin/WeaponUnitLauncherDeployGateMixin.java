package org.ywzj.rvp.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.LauncherDeployRuntimeManager;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitLauncherDeployGateMixin {

    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void rvp$gateShootByLauncherDeploy(int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        AbstractVehicle vehicle = self.getVehicle();
        if (vehicle.level().isClientSide()) {
            return;
        }

        AbstractVehicleWeapon<?> firedWeapon = weaponIndex >= 0 && weaponIndex < self.indexedWeapons.size()
                ? self.indexedWeapons.get(weaponIndex)
                : null;
        RVP_LauncherDeployConfig config = rvp$findConfig(vehicle, self, firedWeapon);
        if (config == null) {
            return;
        }

        LauncherDeployRuntimeManager.Snapshot snapshot = LauncherDeployRuntimeManager.get(vehicle.getId(), config.id());
        LauncherDeployRuntimeManager.State state = snapshot == null ? LauncherDeployRuntimeManager.State.CLOSED : snapshot.state();
        double speedKph = snapshot == null ? vehicle.getDeltaMovement().length() * 20.0 * 3.6 : snapshot.speedKph();

        if (state == LauncherDeployRuntimeManager.State.CLOSED && config.blockFireWhenClosed()) {
            rvp$deny(operator, "发射架未展开");
            ci.cancel();
            return;
        }
        if (state == LauncherDeployRuntimeManager.State.DEPLOYING && config.blockFireWhenDeploying()) {
            rvp$deny(operator, "发射架展开中");
            ci.cancel();
            return;
        }
        if (state == LauncherDeployRuntimeManager.State.RETRACTING && config.blockFireWhenRetracting()) {
            rvp$deny(operator, "发射架收回中");
            ci.cancel();
            return;
        }
        if (config.blockFireWhenSpeeding() && speedKph >= config.retractSpeedMin()) {
            rvp$deny(operator, "车速过高，无法发射");
            ci.cancel();
        }
    }

    @Unique
    private static RVP_LauncherDeployConfig rvp$findConfig(AbstractVehicle vehicle,
                                                           WeaponUnit weaponUnit,
                                                           @Nullable AbstractVehicleWeapon<?> firedWeapon) {
        Set<String> candidateUnitIds = new LinkedHashSet<>();
        rvp$collectWeaponUnitIds(candidateUnitIds, weaponUnit);
        if (firedWeapon != null) {
            rvp$collectWeaponUnitIds(candidateUnitIds, firedWeapon.getWeaponUnit());
        }
        for (RVP_LauncherDeployConfig config : RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId())) {
            if (candidateUnitIds.stream().anyMatch(config::appliesToWeaponUnit)
                    || candidateUnitIds.contains(config.pitchPartUnitId())) {
                return config;
            }
        }
        return null;
    }

    @Unique
    private static void rvp$collectWeaponUnitIds(Set<String> out, @Nullable WeaponUnit weaponUnit) {
        WeaponUnit current = weaponUnit;
        while (current != null) {
            out.add(current.getId());
            current = current.getParentWeaponUnit();
        }
        if (weaponUnit != null) {
            for (WeaponUnit sub : weaponUnit.getSubWeaponUnits()) {
                out.add(sub.getId());
            }
        }
    }

    @Unique
    private static void rvp$deny(@Nullable LivingEntity operator, String message) {
        if (operator instanceof Player player) {
            player.displayClientMessage(Component.literal(message), true);
        }
    }
}
