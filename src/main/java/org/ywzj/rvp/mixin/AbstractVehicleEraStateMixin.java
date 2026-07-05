package org.ywzj.rvp.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.RVPEraStateAccess;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

@Mixin(value = AbstractVehicle.class, remap = false)
public abstract class AbstractVehicleEraStateMixin implements RVPEraStateAccess {

    @Unique
    private static final String RVP_ERA_INACTIVE_TAG = "RvpEraInactiveBones";

    @Unique
    private final Set<String> rvp$inactiveEraBones = new HashSet<>();

    @Override
    public boolean rvp_isEraActive(String boneName) {
        String normalized = rvp$normalizeBone(boneName);
        return normalized != null && !rvp$inactiveEraBones.contains(normalized);
    }

    @Override
    public boolean rvp$consumeEra(String boneName) {
        String normalized = rvp$normalizeBone(boneName);
        return normalized != null && rvp$inactiveEraBones.add(normalized);
    }

    @Override
    public void rvp$setInactiveEraBones(Collection<String> boneNames) {
        rvp$inactiveEraBones.clear();
        if (boneNames == null) {
            return;
        }
        for (String boneName : boneNames) {
            String normalized = rvp$normalizeBone(boneName);
            if (normalized != null) {
                rvp$inactiveEraBones.add(normalized);
            }
        }
    }

    @Override
    public Set<String> rvp$getInactiveEraBones() {
        return Set.copyOf(rvp$inactiveEraBones);
    }

    @Override
    public boolean rvp$retainEraBones(Collection<String> validBoneNames) {
        Set<String> valid = new HashSet<>();
        if (validBoneNames != null) {
            for (String boneName : validBoneNames) {
                String normalized = rvp$normalizeBone(boneName);
                if (normalized != null) {
                    valid.add(normalized);
                }
            }
        }
        return rvp$inactiveEraBones.removeIf(boneName -> !valid.contains(boneName));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"), remap = false)
    private void rvp$saveEraState(CompoundTag compound, CallbackInfo ci) {
        ListTag list = new ListTag();
        for (String boneName : new TreeSet<>(rvp$inactiveEraBones)) {
            list.add(StringTag.valueOf(boneName));
        }
        compound.put(RVP_ERA_INACTIVE_TAG, list);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"), remap = false)
    private void rvp$readEraState(CompoundTag compound, CallbackInfo ci) {
        rvp$inactiveEraBones.clear();
        if (!compound.contains(RVP_ERA_INACTIVE_TAG, Tag.TAG_LIST)) {
            return;
        }
        ListTag list = compound.getList(RVP_ERA_INACTIVE_TAG, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String normalized = rvp$normalizeBone(list.getString(i));
            if (normalized != null) {
                rvp$inactiveEraBones.add(normalized);
            }
        }
    }

    @Inject(method = "writeSpawnData", at = @At("TAIL"), remap = false)
    private void rvp$writeEraSpawnData(FriendlyByteBuf buffer, CallbackInfo ci) {
        buffer.writeVarInt(rvp$inactiveEraBones.size());
        for (String boneName : new TreeSet<>(rvp$inactiveEraBones)) {
            buffer.writeUtf(boneName, 128);
        }
    }

    @Inject(method = "readSpawnData", at = @At("TAIL"), remap = false)
    private void rvp$readEraSpawnData(FriendlyByteBuf buffer, CallbackInfo ci) {
        rvp$inactiveEraBones.clear();
        int size = buffer.readVarInt();
        for (int i = 0; i < size; i++) {
            String normalized = rvp$normalizeBone(buffer.readUtf(128));
            if (normalized != null) {
                rvp$inactiveEraBones.add(normalized);
            }
        }
    }

    @Unique
    private static @Nullable String rvp$normalizeBone(@Nullable String boneName) {
        if (boneName == null) {
            return null;
        }
        String normalized = boneName.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
