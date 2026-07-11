package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.WeaponBayManualOverrideManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.function.Supplier;

@Mixin(value = ClientVehicleAction.class, remap = false)
public abstract class ClientVehicleActionWeaponBayOverrideMixin {

    @Inject(method = "onClientMessageReceived", at = @At("HEAD"), remap = false)
    private static void rvp$markWeaponBayManualOverride(ClientVehicleAction message,
                                                        Supplier<NetworkEvent.Context> ctxSupplier,
                                                        CallbackInfo ci) {
        if (!message.togglePartUnitState) {
            return;
        }
        NetworkEvent.Context context = ctxSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        Level level = player.level();
        Entity entity = level.getEntity(message.vehicleEntityId);
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (message.partUnitIndex < 0 || message.partUnitIndex >= vehicle.getPartUnits().size()) {
            return;
        }
        PartUnit<?> partUnit = vehicle.getPartUnits().get(message.partUnitIndex);
        if (!(partUnit instanceof WeaponBayUnit weaponBayUnit)) {
            return;
        }
        WeaponUnit owner = rvp$findOwner(vehicle, weaponBayUnit);
        if (owner == null) {
            return;
        }
        WeaponBayManualOverrideManager.markManualOverride(
                vehicle.getId(),
                owner.getIndex(),
                owner.getCurrentWeaponIndex(),
                owner.getCurrentSecondaryWeaponIndex()
        );
    }

    @Unique
    private static WeaponUnit rvp$findOwner(AbstractVehicle vehicle, WeaponBayUnit weaponBayUnit) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit && weaponUnit.weaponBayUnits.containsValue(weaponBayUnit)) {
                return weaponUnit;
            }
        }
        return null;
    }
}
