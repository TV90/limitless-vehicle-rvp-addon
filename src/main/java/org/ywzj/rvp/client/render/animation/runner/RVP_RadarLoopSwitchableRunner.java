package org.ywzj.rvp.client.render.animation.runner;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import com.maydaymemory.mae.basic.DummyPose;
import com.maydaymemory.mae.basic.Pose;
import com.maydaymemory.mae.control.runner.AnimationContext;
import com.maydaymemory.mae.control.runner.AnimationRunner;
import com.maydaymemory.mae.control.runner.LoopingState;
import com.maydaymemory.mae.control.runner.PauseState;
import com.maydaymemory.mae.control.runner.PlayingState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.client.render.animation.runner.SwitchableRunner;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

import java.util.function.BooleanSupplier;

public class RVP_RadarLoopSwitchableRunner extends SwitchableRunner {

    private final SwitchableUnit<?> unit;
    private final boolean invert;
    private final boolean loopWhenOn;
    private final AnimationRunner runner;
    // 可折叠雷达只在基座到达扫描姿态后启动旋转。
    private final BooleanSupplier scanReady;
    // 扫描首尾姿态相同时，指定最远半圈的归位时间（秒）。
    private final float sweepReturnSeconds;
    private boolean lastState;

    public RVP_RadarLoopSwitchableRunner(SwitchableUnit<?> unit, BedrockAnimation animation, boolean invert) {
        this(unit, animation, invert, () -> true, 0.0f);
    }

    public RVP_RadarLoopSwitchableRunner(SwitchableUnit<?> unit, BedrockAnimation animation, boolean invert,
                                       BooleanSupplier scanReady, float sweepReturnSeconds) {
        super(unit, animation, invert);
        this.unit = unit;
        this.invert = invert;
        this.scanReady = scanReady;
        this.sweepReturnSeconds = Float.isFinite(sweepReturnSeconds) ? Math.max(0.0f, sweepReturnSeconds) : 0.0f;
        this.loopWhenOn = !invert && unit instanceof RadarUnitSwitchableAdapter;
        this.lastState = unit.isOn() && scanReady.getAsBoolean();
        AnimationContext animContext = new AnimationContext(animation.getSpecifiedEndTimeS());
        this.runner = new AnimationRunner(animation, animContext);

        boolean effectiveState = invert ? !lastState : lastState;
        animContext.setProgress(effectiveState ? animation.getSpecifiedEndTimeS() : 0.0f);
        this.runner.setState(new PauseState());
    }

    @Override
    public void tick() {
        runner.tick();
        if (unit == null) {
            return;
        }
        // 同时检查雷达开关和基座进度，收拢时让阵面先归位。
        boolean active = unit.isOn() && scanReady.getAsBoolean();
        if (active != lastState) {
            AbstractVehicle vehicle = unit.getVehicle();
            vehicle.level().playSound(LocalVehiclePlayer.instance.getPlayer(), vehicle.blockPosition(),
                    active ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE,
                    SoundSource.PLAYERS, 1f, 1f);
            float speed = active != invert ? 1.0f : -1.0f;
            if (!active && loopWhenOn && sweepReturnSeconds > 0.0f) {
                // 选择较近的轨道端点，给基座收拢留出余量。
                var context = runner.getAnimationContext();
                float halfCycle = context.getMaxProgressInSecond() * 0.5f;
                float returnSpeed = Math.max(1.0f, halfCycle / Math.max(0.05f, sweepReturnSeconds));
                speed = context.getProgressInSecond() >= halfCycle ? returnSpeed : -returnSpeed;
            }
            PlayingState playingState = new PlayingState(System::nanoTime, PauseState::new);
            playingState.setSpeed(speed);
            runner.setState(playingState);
            lastState = active;
            return;
        }
        if (loopWhenOn && active && runner.getState() instanceof PauseState) {
            ((AnimationContext) runner.getAnimationContext()).setProgress(0.0f);
            runner.setState(new LoopingState(System::nanoTime));
        }
    }

    /** 读取基座收拢轨道的播放进度（秒），供同车的扫描轨道读取。 */
    public double getProgressInSecond() {
        return runner.getAnimationContext().getProgressInSecond();
    }

    @Override
    public Pose evaluate() {
        return runner != null ? runner.evaluate() : DummyPose.INSTANCE;
    }

    @Override
    public boolean isInvert() {
        return invert;
    }
}
