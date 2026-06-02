package org.ywzj.rvp.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.all.RvpEntities;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.item.VehicleItem;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

public class GunnerSpawnerItem extends VehicleItem {

    private static final String TAG_PREFERRED_SEAT = "PreferredSeat";
    private static final String TAG_PROFILE = "ProfileId";
    private static final int AUTO_SEAT = -1;
    private static final int MAX_SEAT_INDEX = 9;
    private static final List<String> PROFILE_ORDER = List.of("default", "ground", "air", "mixed", "friendly", "enemy", "team");

    public GunnerSpawnerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && hand == InteractionHand.MAIN_HAND) {
            if (player.isShiftKeyDown()) {
                int nextSeat = getPreferredSeat(stack) + 1;
                if (nextSeat > MAX_SEAT_INDEX) {
                    nextSeat = AUTO_SEAT;
                }
                setPreferredSeat(stack, nextSeat);
                player.displayClientMessage(Component.translatable("tips.ywzj_rvp.gunner_spawner.seat", getSeatLabel(nextSeat)), true);
            } else {
                String nextProfile = nextProfileId(stack);
                setProfileId(stack, nextProfile);
                player.displayClientMessage(Component.translatable("tips.ywzj_rvp.gunner_spawner.profile", getProfileLabel(nextProfile)), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public InteractionResult interactEntity(ItemStack itemStack, Player player, Entity target, InteractionHand hand) {
        if (player.level().isClientSide() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(player.level().isClientSide());
        }

        if (target instanceof GunnerEntity gunner) {
            if (!gunner.isOwnedBy(player) && !player.getAbilities().instabuild) {
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
        gunner.initOwner(player);
        gunner.setProfileId(getProfileId(itemStack));
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
        tooltipComponents.add(Component.translatable("tips.ywzj_rvp.gunner_spawner.current_profile", getProfileLabel(getProfileId(stack)))
                .withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.translatable("tips.ywzj_rvp.gunner_spawner.hint").withStyle(ChatFormatting.DARK_GRAY));
        tooltipComponents.add(Component.translatable("tips.ywzj_rvp.gunner_spawner.hint_profile").withStyle(ChatFormatting.DARK_GRAY));
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

    private static String getProfileId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        return tag.contains(TAG_PROFILE) ? tag.getString(TAG_PROFILE) : GunnerProfileManager.DEFAULT_PROFILE_ID.toString();
    }

    private static void setProfileId(ItemStack stack, String profileId) {
        stack.getOrCreateTag().putString(TAG_PROFILE, profileId);
    }

    private static String nextProfileId(ItemStack stack) {
        String current = getProfileId(stack);
        String path = ResourceLocation.tryParse(current) != null ? ResourceLocation.tryParse(current).getPath() : current;
        int idx = PROFILE_ORDER.indexOf(path);
        String next = PROFILE_ORDER.get((idx + 1 + PROFILE_ORDER.size()) % PROFILE_ORDER.size());
        return YwzjRvp.modLocation(next).toString();
    }

    private static Component getSeatLabel(int seatIndex) {
        if (seatIndex < 0) {
            return Component.translatable("tips.ywzj_rvp.gunner_spawner.auto");
        }
        return Component.translatable("tips.ywzj_rvp.gunner_spawner.seat_index", seatIndex);
    }

    private static Component getProfileLabel(String profileId) {
        ResourceLocation id = ResourceLocation.tryParse(profileId);
        String path = id == null ? profileId : id.getPath();
        return Component.translatable("tips.ywzj_rvp.gunner_spawner.profile." + path);
    }
}
