package org.ywzj.rvp.vehicle;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.ywzj.rvp.config.LauncherDeployLocalState;
import org.ywzj.rvp.config.LauncherDeployPoseHelper;
import org.ywzj.rvp.config.LauncherDeployRuntimeManager;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.mixin.accessor.SwitchableUnitAccessor;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发射架部署状态机（替代被删 {@code AbstractVehicleLauncherDeployMixin} 的 tick 逻辑）。
 *
 * <p>每 tick 对每个载具推进部署/收回状态机，同步 Switchable 部件开合、计算并应用俯仰角，
 * 并把快照写入 {@link LauncherDeployRuntimeManager}（射击门控 / 姿态旁路读取）。</p>
 *
 * <p>服务端与客户端各跑一份确定性状态机（输入为速度 / 乘客 / 配置，双端一致），
 * 客户端由此获得姿态动画与门控数据，无需额外网络同步。</p>
 */
public final class LauncherDeployStateMachine {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<Integer, Map<String, LauncherDeployLocalState>> STATES = new HashMap<>();
    private static final Map<String, Long> TRANSITION_LOG_THROTTLE = new HashMap<>();

    private LauncherDeployStateMachine() {
    }

    public static void tick(AbstractVehicle vehicle) {
        if (vehicle.isRemoved()) {
            clear(vehicle.getId());
            return;
        }
        List<RVP_LauncherDeployConfig> configs = RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId());
        if (configs.isEmpty()) {
            clear(vehicle.getId());
            return;
        }

        Map<String, LauncherDeployLocalState> localStates =
                STATES.computeIfAbsent(vehicle.getId(), ignored -> new HashMap<>());
        localStates.keySet().removeIf(id -> configs.stream().noneMatch(config -> config.id().equals(id)));

        double speedKph = vehicle.getDeltaMovement().length() * 20.0 * 3.6;
        boolean hasPlayer = vehicle.getPassengers().stream()
                .anyMatch(p -> p instanceof Player || p instanceof GunnerEntity);

        for (RVP_LauncherDeployConfig config : configs) {
            LauncherDeployLocalState state = localStates.computeIfAbsent(
                    config.id(),
                    ignored -> new LauncherDeployLocalState(LauncherDeployRuntimeManager.State.CLOSED, 0)
            );

            advanceState(config, state, speedKph, hasPlayer);
            syncSwitchable(vehicle, config, state.state);

            float currentPitch = resolveCurrentPitch(config, state);
            applyPitch(vehicle, config, currentPitch);

            // 节流日志：每 100 tick 记录一次状态/俯仰，便于确认部署是否推进
            long gameTime = vehicle.level().getGameTime();
            String key = vehicle.getId() + ":" + config.id();
            Long lastLog = TRANSITION_LOG_THROTTLE.get(key);
            if (lastLog == null || gameTime - lastLog >= 100) {
                TRANSITION_LOG_THROTTLE.put(key, gameTime);
                LOGGER.info("[RVP-LaunchDeploy] 载具={} config={} state={} progress={} pitch={} speedKph={}",
                        vehicle.getVehicleId(), config.id(), state.state, state.progressTick, currentPitch, speedKph);
            }

            LauncherDeployRuntimeManager.put(
                    vehicle.getId(),
                    config.id(),
                    new LauncherDeployRuntimeManager.Snapshot(state.state, state.progressTick, currentPitch, speedKph)
            );
        }
    }

    /** 每 tick 应用“武器站 tick 后姿态旁路”（替代被删 WeaponUnitLauncherDeployPoseBypassMixin）。 */
    public static void applyWeaponUnitPose(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                LauncherDeployPoseHelper.applyRuntimePitchForWeaponUnit(weaponUnit);
            }
        }
    }

    public static void clear(int vehicleId) {
        STATES.remove(vehicleId);
        LauncherDeployRuntimeManager.clearVehicle(vehicleId);
    }

    private static void advanceState(RVP_LauncherDeployConfig config, LauncherDeployLocalState state,
                                     double speedKph, boolean hasPlayer) {
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
                    state.progressTick = mapDeployToRetractProgress(config, state.progressTick);
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
                    state.progressTick = mapRetractToDeployProgress(config, state.progressTick);
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

    private static void syncSwitchable(AbstractVehicle vehicle, RVP_LauncherDeployConfig config,
                                       LauncherDeployRuntimeManager.State state) {
        if (config.partUnitId().isBlank()) {
            return;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(config.partUnitId()).orElse(null);
        if (!(partUnit instanceof SwitchableUnit<?> switchableUnit)) {
            return;
        }
        boolean shouldBeOn = state == LauncherDeployRuntimeManager.State.DEPLOYING
                || state == LauncherDeployRuntimeManager.State.OPEN;
        if (((SwitchableUnitAccessor) switchableUnit).isOnField() != shouldBeOn) {
            ((SwitchableUnitAccessor) switchableUnit).setOnField(shouldBeOn);
        }
    }

    private static float resolveCurrentPitch(RVP_LauncherDeployConfig config, LauncherDeployLocalState state) {
        return switch (state.state) {
            case CLOSED -> config.stowedPitch();
            case OPEN -> config.deployedPitch();
            case DEPLOYING -> lerp(
                    config.stowedPitch(),
                    config.deployedPitch(),
                    config.deployTimeTick() <= 0 ? 1.0f : (float) state.progressTick / config.deployTimeTick()
            );
            case RETRACTING -> lerp(
                    config.deployedPitch(),
                    config.stowedPitch(),
                    config.retractTimeTick() <= 0 ? 1.0f : (float) state.progressTick / config.retractTimeTick()
            );
        };
    }

    private static void applyPitch(AbstractVehicle vehicle, RVP_LauncherDeployConfig config, float pitch) {
        if (config.pitchPartUnitId().isBlank()) {
            return;
        }
        PartUnit<?> partUnit = vehicle.getPartUnit(config.pitchPartUnitId()).orElse(null);
        if (partUnit == null) {
            return;
        }
        LauncherDeployPoseHelper.applyPitch(partUnit, config, pitch);
    }

    private static float lerp(float start, float end, float progress) {
        progress = Math.max(0.0f, Math.min(1.0f, progress));
        return start + (end - start) * progress;
    }

    private static int mapDeployToRetractProgress(RVP_LauncherDeployConfig config, int deployProgressTick) {
        int deployTime = Math.max(1, config.deployTimeTick());
        int retractTime = Math.max(1, config.retractTimeTick());
        float deployFraction = Math.max(0.0f, Math.min(1.0f, (float) deployProgressTick / deployTime));
        float retractFraction = 1.0f - deployFraction;
        return Math.max(0, Math.min(retractTime, Math.round(retractFraction * retractTime)));
    }

    private static int mapRetractToDeployProgress(RVP_LauncherDeployConfig config, int retractProgressTick) {
        int deployTime = Math.max(1, config.deployTimeTick());
        int retractTime = Math.max(1, config.retractTimeTick());
        float retractFraction = Math.max(0.0f, Math.min(1.0f, (float) retractProgressTick / retractTime));
        float deployFraction = 1.0f - retractFraction;
        return Math.max(0, Math.min(deployTime, Math.round(deployFraction * deployTime)));
    }
}
