package org.ywzj.rvp.mixin;

import com.mojang.math.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.LauncherDeployPoseHelper;
import org.ywzj.rvp.config.LauncherDeployLocalState;
import org.ywzj.rvp.config.LauncherDeployRuntimeManager;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.mixin.accessor.SwitchableUnitAccessor;
import org.ywzj.rvp.mixin.accessor.WeaponUnitAccessor;
import org.ywzj.vehicle.custom.part.data.PartUnitData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = AbstractVehicle.class, remap = false)
public abstract class AbstractVehicleLauncherDeployMixin {

    @Unique
    private static final String RVP_LAUNCHER_DEPLOY_TAG = "RvpLauncherDeployStates";

    @Unique
    private final Map<String, LauncherDeployLocalState> rvp$launcherDeployStates = new HashMap<>();

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"), remap = true)
    private void rvp$saveLauncherDeployState(CompoundTag compound, CallbackInfo ci) {
        if (rvp$launcherDeployStates.isEmpty()) {
            return;
        }
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, LauncherDeployLocalState> entry : rvp$launcherDeployStates.entrySet()) {
            LauncherDeployLocalState state = entry.getValue();
            CompoundTag stateTag = new CompoundTag();
            stateTag.putString("state", state.state.name());
            stateTag.putInt("progressTick", state.progressTick);
            root.put(entry.getKey(), stateTag);
        }
        compound.put(RVP_LAUNCHER_DEPLOY_TAG, root);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"), remap = true)
    private void rvp$readLauncherDeployState(CompoundTag compound, CallbackInfo ci) {
        rvp$launcherDeployStates.clear();
        if (!compound.contains(RVP_LAUNCHER_DEPLOY_TAG, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag root = compound.getCompound(RVP_LAUNCHER_DEPLOY_TAG);
        for (String key : root.getAllKeys()) {
            CompoundTag stateTag = root.getCompound(key);
            LauncherDeployRuntimeManager.State state = rvp$parseState(stateTag.getString("state"));
            int progressTick = Math.max(0, stateTag.getInt("progressTick"));
            rvp$launcherDeployStates.put(key, new LauncherDeployLocalState(state, progressTick));
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = true)
    private void rvp$tickLauncherDeploy(CallbackInfo ci) {
        AbstractVehicle vehicle = (AbstractVehicle) (Object) this;
        if (vehicle.isRemoved()) {
            LauncherDeployRuntimeManager.clearVehicle(vehicle.getId());
            return;
        }

        List<RVP_LauncherDeployConfig> configs = RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId());
        if (configs.isEmpty()) {
            rvp$launcherDeployStates.clear();
            LauncherDeployRuntimeManager.clearVehicle(vehicle.getId());
            return;
        }

        rvp$launcherDeployStates.keySet().removeIf(id -> configs.stream().noneMatch(config -> config.id().equals(id)));

        double speedKph = vehicle.getDeltaMovement().length() * 20.0 * 3.6;
        boolean hasPlayer = vehicle.getPassengers().stream()
                .anyMatch(p -> p instanceof Player || p instanceof GunnerEntity);

        for (RVP_LauncherDeployConfig config : configs) {
            LauncherDeployLocalState state = rvp$launcherDeployStates.computeIfAbsent(
                    config.id(),
                    ignored -> new LauncherDeployLocalState(LauncherDeployRuntimeManager.State.CLOSED, 0)
            );

            rvp$advanceState(config, state, speedKph, hasPlayer);
            rvp$syncSwitchable(vehicle, config, state.state);

            float currentPitch = rvp$resolveCurrentPitch(config, state);
            rvp$applyPitch(vehicle, config, currentPitch);

            LauncherDeployRuntimeManager.put(
                    vehicle.getId(),
                    config.id(),
                    new LauncherDeployRuntimeManager.Snapshot(state.state, state.progressTick, currentPitch, speedKph)
            );
        }
    }

    @Inject(method = "onRemovedFromWorld", at = @At("TAIL"))
    private void rvp$clearLauncherDeployRuntime(CallbackInfo ci) {
        AbstractVehicle vehicle = (AbstractVehicle) (Object) this;
        LauncherDeployRuntimeManager.clearVehicle(vehicle.getId());
    }

    @Unique
    private void rvp$advanceState(RVP_LauncherDeployConfig config, LauncherDeployLocalState state, double speedKph, boolean hasPlayer) {
        boolean canDeploy = config.autoDeploy() && speedKph <= config.deploySpeedMax()
                && (!config.requirePlayerPresent() || hasPlayer);
        boolean shouldRetract = config.autoRetract()
                && (speedKph >= config.retractSpeedMin() || (config.requirePlayerPresent() && !hasPlayer));

        switch (state.state) {
            case CLOSED -> {
                state.progressTick = 0;
                if (canDeploy) {
                    state.state = LauncherDeployRuntimeManager.State.DEPLOYING;
                    state.progressTick = 0;
                }
            }
            case DEPLOYING -> {
                if (shouldRetract) {
                    state.state = LauncherDeployRuntimeManager.State.RETRACTING;
                    state.progressTick = rvp$mapDeployToRetractProgress(config, state.progressTick);
                    return;
                }
                if (state.progressTick < config.deployTimeTick()) {
                    state.progressTick++;
                }
                if (state.progressTick >= config.deployTimeTick()) {
                    state.state = LauncherDeployRuntimeManager.State.OPEN;
                    state.progressTick = config.deployTimeTick();
                }
            }
            case OPEN -> {
                state.progressTick = config.deployTimeTick();
                if (shouldRetract) {
                    state.state = LauncherDeployRuntimeManager.State.RETRACTING;
                    state.progressTick = 0;
                }
            }
            case RETRACTING -> {
                if (!shouldRetract && canDeploy) {
                    state.state = LauncherDeployRuntimeManager.State.DEPLOYING;
                    state.progressTick = rvp$mapRetractToDeployProgress(config, state.progressTick);
                    return;
                }
                if (state.progressTick < config.retractTimeTick()) {
                    state.progressTick++;
                }
                if (state.progressTick >= config.retractTimeTick()) {
                    state.state = LauncherDeployRuntimeManager.State.CLOSED;
                    state.progressTick = 0;
                }
            }
        }
    }

    @Unique
    private void rvp$syncSwitchable(AbstractVehicle vehicle, RVP_LauncherDeployConfig config, LauncherDeployRuntimeManager.State state) {
        if (config.partUnitId().isBlank()) {
            return;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(config.partUnitId()).orElse(null);
        if (!(partUnit instanceof SwitchableUnit<?> switchableUnit)) {
            return;
        }
        boolean shouldBeOn = state == LauncherDeployRuntimeManager.State.DEPLOYING || state == LauncherDeployRuntimeManager.State.OPEN;
        if (((SwitchableUnitAccessor) switchableUnit).isOnField() != shouldBeOn) {
            ((SwitchableUnitAccessor) switchableUnit).setOnField(shouldBeOn);
        }
    }

    @Unique
    private float rvp$resolveCurrentPitch(RVP_LauncherDeployConfig config, LauncherDeployLocalState state) {
        return switch (state.state) {
            case CLOSED -> config.stowedPitch();
            case OPEN -> config.deployedPitch();
            case DEPLOYING -> rvp$lerp(
                    config.stowedPitch(),
                    config.deployedPitch(),
                    config.deployTimeTick() <= 0 ? 1.0f : (float) state.progressTick / config.deployTimeTick()
            );
            case RETRACTING -> rvp$lerp(
                    config.deployedPitch(),
                    config.stowedPitch(),
                    config.retractTimeTick() <= 0 ? 1.0f : (float) state.progressTick / config.retractTimeTick()
            );
        };
    }

    @Unique
    private void rvp$applyPitch(AbstractVehicle vehicle, RVP_LauncherDeployConfig config, float pitch) {
        if (config.pitchPartUnitId().isBlank()) {
            return;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(config.pitchPartUnitId()).orElse(null);
        if (partUnit == null) {
            return;
        }
        LauncherDeployPoseHelper.applyPitch(partUnit, config, pitch);
    }

    @Unique
    private static LauncherDeployRuntimeManager.State rvp$parseState(String value) {
        try {
            return LauncherDeployRuntimeManager.State.valueOf(value);
        } catch (Exception ignored) {
            return LauncherDeployRuntimeManager.State.CLOSED;
        }
    }

    @Unique
    private static float rvp$lerp(float start, float end, float progress) {
        progress = Math.max(0.0f, Math.min(1.0f, progress));
        return start + (end - start) * progress;
    }

    @Unique
    private static int rvp$mapDeployToRetractProgress(RVP_LauncherDeployConfig config, int deployProgressTick) {
        int deployTime = Math.max(1, config.deployTimeTick());
        int retractTime = Math.max(1, config.retractTimeTick());
        float deployFraction = Math.max(0.0f, Math.min(1.0f, (float) deployProgressTick / deployTime));
        float retractFraction = 1.0f - deployFraction;
        return Math.max(0, Math.min(retractTime, Math.round(retractFraction * retractTime)));
    }

    @Unique
    private static int rvp$mapRetractToDeployProgress(RVP_LauncherDeployConfig config, int retractProgressTick) {
        int deployTime = Math.max(1, config.deployTimeTick());
        int retractTime = Math.max(1, config.retractTimeTick());
        float retractFraction = Math.max(0.0f, Math.min(1.0f, (float) retractProgressTick / retractTime));
        float deployFraction = 1.0f - retractFraction;
        return Math.max(0, Math.min(deployTime, Math.round(deployFraction * deployTime)));
    }
}
