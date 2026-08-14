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
import org.ywzj.vehicle.vehicle.part.RotatableUnit;
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
        // hasPlayer：驾驶员（本地玩家/炮手）也算有人——单机下客户端实体 getPassengers() 可能不含本地玩家，
        // 若只查乘客会让 requirePlayerPresent 的载具在客户端恒判定"无人"→ 刚展开就被 retract、后续再展不开
        net.minecraft.world.entity.Entity driver = vehicle.getDriver();
        boolean hasPlayer = driver instanceof Player || driver instanceof GunnerEntity
                || vehicle.getPassengers().stream()
                .anyMatch(p -> p instanceof Player || p instanceof GunnerEntity);

        for (RVP_LauncherDeployConfig config : configs) {
            LauncherDeployLocalState state = localStates.computeIfAbsent(
                    config.id(),
                    ignored -> new LauncherDeployLocalState(LauncherDeployRuntimeManager.State.CLOSED, 0)
            );

            advanceState(config, state, speedKph, hasPlayer);
            syncSwitchable(vehicle, config, state.state);

            float currentPitch = resolveCurrentPitch(config, state);
            driveLauncherPitch(vehicle, config, currentPitch);

            // 节流日志：每 100 tick 记录一次状态/俯仰，便于确认部署是否推进
            long gameTime = vehicle.level().getGameTime();
            String key = vehicle.getId() + ":" + config.id();
            Long lastLog = TRANSITION_LOG_THROTTLE.get(key);
            if (lastLog == null || gameTime - lastLog >= 100) {
                TRANSITION_LOG_THROTTLE.put(key, gameTime);
                net.minecraft.world.entity.Entity drv = vehicle.getDriver();
                LOGGER.info("[RVP-LaunchDeploy] 载具={} config={} state={} progress={} pitch={} speedKph={} hasPlayer={} driver={} passengers={}",
                        vehicle.getVehicleId(), config.id(), state.state, state.progressTick, currentPitch, speedKph,
                        hasPlayer, drv == null ? "null" : drv.getClass().getSimpleName(),
                        vehicle.getPassengers().size());
            }

            LauncherDeployRuntimeManager.put(
                    vehicle.getId(),
                    config.id(),
                    new LauncherDeployRuntimeManager.Snapshot(state.state, state.progressTick, currentPitch, speedKph)
            );
        }
    }

    /**
     * 以本体风格驱动发射架部件俯仰（双端）。
     *
     * <p>与本体官方 htf5980 起竖同机制：本体在 {@code HTF5980.tick()} 里对导弹部件调用
     * {@code missile.setXAimRot(pipe.isOn() ? -90 : 0)}，由部件自身的 {@code tickRot()} 把
     * {@code xRot} 转到目标角，随后本体的 {@code updateRot()} 把 {@code xTurnGroup}
     *（9k720 即 launcher_pitch_barrel，含发射管 cube OBB）旋转 → 结构 OBB 跟随。</p>
     *
     * <p>这里在状态机每 tick（服务端/客户端各一份）把发射架部件的 {@code xRot/xAimRot}
     * 直接设为目标俯仰，并放开俯仰角范围 / 关闭跟随瞄准（rotByAim），保证本体 updateRot
     * 写出正确的结构骨旋转。全程使用本体公共 API，无需 Mixin。</p>
     */
    public static void driveLauncherPitch(AbstractVehicle vehicle, RVP_LauncherDeployConfig config, float pitch) {
        if (vehicle == null || config == null || config.pitchPartUnitId() == null
                || config.pitchPartUnitId().isBlank()) {
            return;
        }
        vehicle.getPartUnit(config.pitchPartUnitId()).ifPresent(partUnit -> {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                // 关闭瞄准跟随，避免父级武器站瞄准覆盖部署俯仰
                weaponUnit.rotByAim = false;
            }
            if (partUnit instanceof RotatableUnit<?> rotatable) {
                driveRotatable(rotatable, config, pitch);
            }
        });
    }

    private static void driveRotatable(RotatableUnit<?> rotatable, RVP_LauncherDeployConfig config, float pitch) {
        // 俯仰角范围锚定 [0, stowed, deployed]，并放宽 ±45 容差（覆盖 xSelfRot 等偏移），
        // 避免本体 tickRot 的 x_rot_max/min 钳制把发射架俯仰拉回 0
        float lo = Math.min(Math.min(config.stowedPitch(), config.deployedPitch()), 0f);
        float hi = Math.max(Math.max(config.stowedPitch(), config.deployedPitch()), 0f);
        rotatable.setXRotMin(lo - 45);
        rotatable.setXRotMax(hi + 45);
        // 旋转速度由部署时长推算（本处每 tick 直接写 xRot，速度仅作兜底）
        rotatable.setXRotSpeed(Math.max(0.1f, (hi - lo) / Math.max(1, config.deployTimeTick())));
        rotatable.setXAimRot(pitch);
        rotatable.setXRot(pitch);
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
