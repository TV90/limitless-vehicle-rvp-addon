package org.ywzj.rvp.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.item.VehicleItem;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

public class FixedProfileGunnerSpawnerItem extends VehicleItem {

    private static final String TAG_PREFERRED_SEAT = "PreferredSeat";
    private static final int AUTO_SEAT = -1;
    private static final int MAX_SEAT_INDEX = 9;

    private final String profileId;
    private final boolean assignOwner;

    public FixedProfileGunnerSpawnerItem(Properties properties, String profileId, boolean assignOwner) {
        super(properties);
        this.profileId = profileId;
        this.assignOwner = assignOwner;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown()) {
            int nextSeat = getPreferredSeat(stack) + 1;
            if (nextSeat > MAX_SEAT_INDEX) {
                nextSeat = AUTO_SEAT;
            }
            setPreferredSeat(stack, nextSeat);
            player.displayClientMessage(Component.translatable("tips.ywzj_rvp.gunner_spawner.seat", getSeatLabel(nextSeat)), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public InteractionResult interactEntity(ItemStack itemStack, Player player, Entity target, InteractionHand hand) {
        if (player.level().isClientSide() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(player.level().isClientSide());
        }

        if (target instanceof GunnerEntity gunner) {
            boolean canRemove = player.getAbilities().instabuild
                    || gunner.isOwnedBy(player)
                    || gunner.getOwnerPlayer() == null;
            if (!canRemove) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.gunner.remove_own_only"), true);
                return InteractionResult.FAIL;
            }
            gunner.discard();
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gunner.removed"), true);
            return InteractionResult.SUCCESS;
        }

        if (!(target instanceof AbstractVehicle vehicle)) {
            return InteractionResult.PASS;
        }

        cleanupInvalidPassengers(vehicle);

        int preferredSeat = getPreferredSeat(itemStack);
        if (preferredSeat != AUTO_SEAT) {
            if (preferredSeat >= vehicle.seats.size()) {
                player.displayClientMessage(Component.translatable("tips.ywzj_rvp.gunner_spawner.seat_missing", preferredSeat), true);
                return InteractionResult.FAIL;
            }
            if (vehicle.seats.get(preferredSeat).passengerId != -1) {
                player.displayClientMessage(Component.translatable("tips.ywzj_rvp.gunner_spawner.seat_busy", preferredSeat), true);
                return InteractionResult.FAIL;
            }
        } else {
            boolean hasEmptySeat = vehicle.seats.stream().anyMatch(seat -> seat.passengerId == -1);
            if (!hasEmptySeat) {
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.gunner.no_empty_seat"), true);
                return InteractionResult.FAIL;
            }
        }

        ServerLevel level = (ServerLevel) player.level();
        GunnerEntity gunner = RvpEntities.GUNNER.get().create(level);
        if (gunner == null) {
            return InteractionResult.FAIL;
        }

        gunner.moveTo(vehicle.getX(), vehicle.getY(), vehicle.getZ(), player.getYRot(), player.getXRot());
        if (assignOwner) {
            gunner.initOwner(player);
        } else {
            gunner.setCustomName(Component.translatable("entity.ywzj_rvp.gunner"));
            gunner.setCustomNameVisible(true);
            gunner.setPersistenceRequired();
        }
        gunner.setProfileId(profileId);

        level.addFreshEntity(gunner);
        if (!gunner.startRiding(vehicle, true)) {
            gunner.discard();
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.gunner.mount_failed"), true);
            return InteractionResult.FAIL;
        }

        if (preferredSeat != AUTO_SEAT) {
            int currentSeat = getCurrentSeatIndex(vehicle, gunner);
            if (currentSeat != preferredSeat && !vehicle.changeSeat(gunner, preferredSeat)) {
                gunner.stopRiding();
                gunner.discard();
                player.displayClientMessage(Component.translatable("message.ywzj_rvp.gunner.seat_switch_failed"), true);
                return InteractionResult.FAIL;
            }
        } else {
            trySelectWeaponSeat(vehicle, gunner);
        }

        player.displayClientMessage(Component.translatable("tips.ywzj_rvp.gunner_spawner.deployed", getSeatLabel(getCurrentSeatIndex(vehicle, gunner))), true);
        return InteractionResult.SUCCESS;
    }

    private static void trySelectWeaponSeat(AbstractVehicle vehicle, GunnerEntity gunner) {
        int originalSeat = getCurrentSeatIndex(vehicle, gunner);
        if (isWeaponSeat(vehicle, gunner)) {
            return;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != -1 && seat.passengerId != gunner.getId()) {
                continue;
            }
            if (!vehicle.changeSeat(gunner, seat.seatIndex)) {
                continue;
            }
            if (isWeaponSeat(vehicle, gunner)) {
                return;
            }
        }
        if (originalSeat >= 0) {
            vehicle.changeSeat(gunner, originalSeat);
        }
    }

    private static boolean isWeaponSeat(AbstractVehicle vehicle, GunnerEntity gunner) {
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(gunner);
        return unit instanceof WeaponUnit weaponUnit && !weaponUnit.getIndexedWeapons().isEmpty();
    }

    private static void cleanupInvalidPassengers(AbstractVehicle vehicle) {
        Level level = vehicle.level();
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == -1) {
                continue;
            }
            if (level.getEntity(seat.passengerId) == null) {
                seat.passengerId = -1;
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        tooltipComponents.add(Component.translatable("tips.ywzj_rvp.gunner_spawner.current", getSeatLabel(getPreferredSeat(stack)))
                .withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tips.ywzj_rvp.gunner_spawner.hint").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
    }

    private static int getCurrentSeatIndex(AbstractVehicle vehicle, GunnerEntity gunner) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == gunner.getId()) {
                return seat.seatIndex;
            }
        }
        return AUTO_SEAT;
    }

    private static int getPreferredSeat(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.contains(TAG_PREFERRED_SEAT) ? tag.getInt(TAG_PREFERRED_SEAT) : AUTO_SEAT;
    }

    private static void setPreferredSeat(ItemStack stack, int seatIndex) {
        stack.getOrCreateTag().putInt(TAG_PREFERRED_SEAT, seatIndex);
    }

    private static Component getSeatLabel(int seatIndex) {
        if (seatIndex < 0) {
            return Component.translatable("tips.ywzj_rvp.gunner_spawner.auto");
        }
        return Component.translatable("tips.ywzj_rvp.gunner_spawner.seat_index", seatIndex);
    }
}
