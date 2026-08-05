package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.function.Supplier;

public record C2SSelectModdingSubWeapon(
        int vehicleEntityId,
        String partId,
        int weaponIndex,
        int subWeaponIndex
) {

    private static final double MAX_INTERACTION_DISTANCE_SQ = 256.0;

    public static void encode(C2SSelectModdingSubWeapon msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleEntityId);
        buf.writeUtf(msg.partId);
        buf.writeInt(msg.weaponIndex);
        buf.writeInt(msg.subWeaponIndex);
    }

    public static C2SSelectModdingSubWeapon decode(FriendlyByteBuf buf) {
        return new C2SSelectModdingSubWeapon(
                buf.readInt(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt()
        );
    }

    public static void handle(C2SSelectModdingSubWeapon msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        context.setPacketHandled(true);
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || !hasModdingTool(player.getItemInHand(InteractionHand.MAIN_HAND), player.getItemInHand(InteractionHand.OFF_HAND))) {
                return;
            }
            if (!(player.level().getEntity(msg.vehicleEntityId) instanceof AbstractVehicle vehicle)) {
                return;
            }
            if (player.distanceToSqr(vehicle) > MAX_INTERACTION_DISTANCE_SQ) {
                return;
            }
            PartUnit<?> partUnit = vehicle.getPartUnit(msg.partId).orElse(null);
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                return;
            }
            if (!RVP_VehicleExtendedConfigManager.INSTANCE.isModdingOnlyMulti(weaponUnit, msg.weaponIndex)) {
                return;
            }
            if (msg.weaponIndex < 0 || msg.weaponIndex >= weaponUnit.weapons.size()) {
                return;
            }
            VehicleMultiWeapons multi = RVP_VehicleExtendedConfigManager.INSTANCE
                    .resolveModdingTargetMulti(weaponUnit, msg.weaponIndex);
            if (multi == null) {
                return;
            }
            if (msg.subWeaponIndex < 0 || msg.subWeaponIndex >= multi.getSubWeapons().size()) {
                return;
            }
            selectVariant(multi, msg.subWeaponIndex);
        });
    }

    private static void selectVariant(VehicleMultiWeapons multi, int targetIndex) {
        int current = multi.getSelectedIndex();
        if (current == targetIndex) {
            return;
        }
        int guard = multi.getSubWeapons().size() + 1;
        while (multi.getSelectedIndex() != targetIndex && guard-- > 0) {
            multi.cycleSubWeapon(true);
        }
    }

    private static boolean hasModdingTool(ItemStack mainHand, ItemStack offHand) {
        return mainHand.is(AllItems.MODDING_TOOL.get()) || offHand.is(AllItems.MODDING_TOOL.get());
    }
}
