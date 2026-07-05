package org.ywzj.rvp.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.ext.AbstractVehicleLinkedUavExt;
import org.ywzj.vehicle.all.AllEntities;
import org.ywzj.vehicle.entity.misc.FakePlayer;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;

import java.util.UUID;

@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleLinkedUavMixin implements AbstractVehicleLinkedUavExt {
    @Unique
    private static final String TAG_PARENT_UUID = "ywzj_rvp_linkedParentVehicleUuid";
    @Unique
    private static final String TAG_CHILD_UUID = "ywzj_rvp_linkedChildVehicleUuid";
    @Unique
    private static final String TAG_LAUNCHER_UUID = "ywzj_rvp_linkedLauncherVehicleUuid";
    @Unique
    private static final String TAG_DEPLOYABLE_INSTANCE = "ywzj_rvp_deployableUavInstance";
    @Unique
    private static final String TAG_ALLOW_SWITCH = "ywzj_rvp_deployableUavAllowControlSwitch";
    @Unique
    private static final String TAG_RETURN_SEAT = "ywzj_rvp_deployableUavReturnSeatIndex";
    @Unique
    private static final String TAG_UAV_ROLE = "ywzj_rvp_deployableUavRole";
    @Unique
    private static final String TAG_DATALINK_ROLE = "ywzj_rvp_datalinkRole";
    @Unique
    private static final String TAG_FAKE_POS_X = "ywzj_rvp_fakeOperatorPositionX";
    @Unique
    private static final String TAG_FAKE_POS_Y = "ywzj_rvp_fakeOperatorPositionY";
    @Unique
    private static final String TAG_FAKE_POS_Z = "ywzj_rvp_fakeOperatorPositionZ";

    @Shadow public boolean uav;
    @Shadow private Vec3 fakeOperatorPosition;
    @Shadow private FakePlayer fakeOperator;

    @Shadow public abstract LivingEntity getDriver();
    @Shadow public abstract void onLeaveVehicle(LivingEntity passenger);

    @Unique
    private UUID ywzj_rvp$linkedParentVehicleUuid;
    @Unique
    private UUID ywzj_rvp$linkedChildVehicleUuid;
    @Unique
    private UUID ywzj_rvp$linkedLauncherVehicleUuid;
    @Unique
    private boolean ywzj_rvp$deployableUavInstance;
    @Unique
    private boolean ywzj_rvp$deployableUavAllowControlSwitch = true;
    @Unique
    private int ywzj_rvp$returnSeatIndex = -1;
    @Unique
    private String ywzj_rvp$deployableUavRole = "none";
    @Unique
    private String ywzj_rvp$datalinkRole = "none";

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void ywzj_rvp$saveLinkedUavState(CompoundTag compound, CallbackInfo ci) {
        if (ywzj_rvp$linkedParentVehicleUuid != null) {
            compound.putUUID(TAG_PARENT_UUID, ywzj_rvp$linkedParentVehicleUuid);
        }
        if (ywzj_rvp$linkedChildVehicleUuid != null) {
            compound.putUUID(TAG_CHILD_UUID, ywzj_rvp$linkedChildVehicleUuid);
        }
        if (ywzj_rvp$linkedLauncherVehicleUuid != null) {
            compound.putUUID(TAG_LAUNCHER_UUID, ywzj_rvp$linkedLauncherVehicleUuid);
        }
        compound.putBoolean(TAG_DEPLOYABLE_INSTANCE, ywzj_rvp$deployableUavInstance);
        compound.putBoolean(TAG_ALLOW_SWITCH, ywzj_rvp$deployableUavAllowControlSwitch);
        compound.putInt(TAG_RETURN_SEAT, ywzj_rvp$returnSeatIndex);
        if (!ywzj_rvp$deployableUavRole.isBlank()) {
            compound.putString(TAG_UAV_ROLE, ywzj_rvp$deployableUavRole);
        }
        if (!ywzj_rvp$datalinkRole.isBlank()) {
            compound.putString(TAG_DATALINK_ROLE, ywzj_rvp$datalinkRole);
        }
        if (ywzj_rvp$isInstanceUavOnly() && fakeOperatorPosition != null) {
            compound.putDouble(TAG_FAKE_POS_X, fakeOperatorPosition.x);
            compound.putDouble(TAG_FAKE_POS_Y, fakeOperatorPosition.y);
            compound.putDouble(TAG_FAKE_POS_Z, fakeOperatorPosition.z);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void ywzj_rvp$readLinkedUavState(CompoundTag compound, CallbackInfo ci) {
        ywzj_rvp$linkedParentVehicleUuid = compound.hasUUID(TAG_PARENT_UUID) ? compound.getUUID(TAG_PARENT_UUID) : null;
        ywzj_rvp$linkedChildVehicleUuid = compound.hasUUID(TAG_CHILD_UUID) ? compound.getUUID(TAG_CHILD_UUID) : null;
        ywzj_rvp$linkedLauncherVehicleUuid = compound.hasUUID(TAG_LAUNCHER_UUID) ? compound.getUUID(TAG_LAUNCHER_UUID) : null;
        ywzj_rvp$deployableUavInstance = compound.getBoolean(TAG_DEPLOYABLE_INSTANCE);
        ywzj_rvp$deployableUavAllowControlSwitch = !compound.contains(TAG_ALLOW_SWITCH) || compound.getBoolean(TAG_ALLOW_SWITCH);
        ywzj_rvp$returnSeatIndex = compound.contains(TAG_RETURN_SEAT) ? compound.getInt(TAG_RETURN_SEAT) : -1;
        ywzj_rvp$deployableUavRole = compound.getString(TAG_UAV_ROLE);
        ywzj_rvp$datalinkRole = compound.getString(TAG_DATALINK_ROLE);
        if (ywzj_rvp$isInstanceUavOnly() && compound.contains(TAG_FAKE_POS_X)) {
            fakeOperatorPosition = new Vec3(
                    compound.getDouble(TAG_FAKE_POS_X),
                    compound.getDouble(TAG_FAKE_POS_Y),
                    compound.getDouble(TAG_FAKE_POS_Z)
            );
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ywzj_rvp$tickDeployableUavChunkLoading(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!ywzj_rvp$isInstanceUavOnly() || self.level().isClientSide()) {
            return;
        }
        EntityUtil.keepChunkLoaded(self, self.position());
        EntityUtil.keepChunkLoaded(self, self.position().add(self.getLookAngle().normalize().scale(16)));
        if (fakeOperatorPosition != null) {
            EntityUtil.keepChunkLoaded(self, fakeOperatorPosition);
        }
    }

    @Inject(method = "onRemovedFromWorld", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$restoreOperatorWhenInstanceUavRemoved(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!ywzj_rvp$isInstanceUavOnly() || self.level().isClientSide()) {
            return;
        }
        if (getDriver() instanceof ServerPlayer serverPlayer && fakeOperator != null) {
            onLeaveVehicle(serverPlayer);
            serverPlayer.unRide();
            Vec3 backPosition = fakeOperator.position();
            serverPlayer.teleportTo(backPosition.x, backPosition.y, backPosition.z);
            serverPlayer.setYRot(fakeOperator.getYRot());
            serverPlayer.setYBodyRot(fakeOperator.yBodyRot);
            serverPlayer.setXRot(fakeOperator.getXRot());
            fakeOperatorPosition = null;
        }
    }

    @Inject(method = "onEnterVehicle", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$spawnFakeOperatorForInstanceUav(LivingEntity livingEntity, CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;
        if (!ywzj_rvp$isInstanceUavOnly() || self.level().isClientSide()) {
            return;
        }
        if (livingEntity instanceof ServerPlayer serverPlayer && self.tickCount != 0) {
            fakeOperatorPosition = livingEntity.position();
            fakeOperator = new FakePlayer(AllEntities.FAKE_PLAYER.get(), self.level());
            fakeOperator.spawn(serverPlayer);
            fakeOperator.setPos(fakeOperatorPosition);
            self.level().addFreshEntity(fakeOperator);
            livingEntity.teleportTo(self.position().x, self.position().y, self.position().z);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$blockDirectInteractWhenInstanceUav(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (ywzj_rvp$isInstanceUavOnly()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "getDismountLocationForPassenger", at = @At("HEAD"), cancellable = true)
    private void ywzj_rvp$getDismountForInstanceUav(LivingEntity passenger, CallbackInfoReturnable<Vec3> cir) {
        if (!ywzj_rvp$isInstanceUavOnly()) {
            return;
        }
        if (fakeOperator != null) {
            Vec3 position = fakeOperator.position();
            fakeOperatorPosition = null;
            passenger.setYRot(fakeOperator.getYRot());
            passenger.setYBodyRot(fakeOperator.yBodyRot);
            passenger.setXRot(fakeOperator.getXRot());
            cir.setReturnValue(position);
        } else if (fakeOperatorPosition != null) {
            cir.setReturnValue(fakeOperatorPosition);
        }
    }

    @Unique
    private boolean ywzj_rvp$isInstanceUavOnly() {
        return ywzj_rvp$deployableUavInstance && !uav;
    }

    @Override
    public UUID ywzj_rvp$getLinkedParentVehicleUuid() {
        return ywzj_rvp$linkedParentVehicleUuid;
    }

    @Override
    public void ywzj_rvp$setLinkedParentVehicleUuid(UUID uuid) {
        ywzj_rvp$linkedParentVehicleUuid = uuid;
    }

    @Override
    public UUID ywzj_rvp$getLinkedChildVehicleUuid() {
        return ywzj_rvp$linkedChildVehicleUuid;
    }

    @Override
    public void ywzj_rvp$setLinkedChildVehicleUuid(UUID uuid) {
        ywzj_rvp$linkedChildVehicleUuid = uuid;
    }

    @Override
    public UUID ywzj_rvp$getLinkedLauncherVehicleUuid() {
        return ywzj_rvp$linkedLauncherVehicleUuid;
    }

    @Override
    public void ywzj_rvp$setLinkedLauncherVehicleUuid(UUID uuid) {
        ywzj_rvp$linkedLauncherVehicleUuid = uuid;
    }

    @Override
    public boolean ywzj_rvp$isDeployableUavInstance() {
        return ywzj_rvp$deployableUavInstance;
    }

    @Override
    public void ywzj_rvp$setDeployableUavInstance(boolean value) {
        ywzj_rvp$deployableUavInstance = value;
    }

    @Override
    public boolean ywzj_rvp$isDeployableUavControlSwitchAllowed() {
        return ywzj_rvp$deployableUavAllowControlSwitch;
    }

    @Override
    public void ywzj_rvp$setDeployableUavControlSwitchAllowed(boolean value) {
        ywzj_rvp$deployableUavAllowControlSwitch = value;
    }

    @Override
    public int ywzj_rvp$getReturnSeatIndex() {
        return ywzj_rvp$returnSeatIndex;
    }

    @Override
    public void ywzj_rvp$setReturnSeatIndex(int seatIndex) {
        ywzj_rvp$returnSeatIndex = seatIndex;
    }

    @Override
    public String ywzj_rvp$getDeployableUavRole() {
        return ywzj_rvp$deployableUavRole;
    }

    @Override
    public void ywzj_rvp$setDeployableUavRole(String role) {
        ywzj_rvp$deployableUavRole = role == null ? "none" : role;
    }

    @Override
    public String ywzj_rvp$getDatalinkRole() {
        return ywzj_rvp$datalinkRole;
    }

    @Override
    public void ywzj_rvp$setDatalinkRole(String role) {
        ywzj_rvp$datalinkRole = role == null ? "none" : role;
    }
}
