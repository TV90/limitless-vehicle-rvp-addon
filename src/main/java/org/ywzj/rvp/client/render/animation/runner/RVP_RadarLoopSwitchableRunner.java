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

public class RVP_RadarLoopSwitchableRunner extends SwitchableRunner {

    private final SwitchableUnit<?> unit;
    private final boolean invert;
    private final boolean loopWhenOn;
    private final AnimationRunner runner;
    private boolean lastState;

    public RVP_RadarLoopSwitchableRunner(SwitchableUnit<?> unit, BedrockAnimation animation, boolean invert) {
        super(unit, animation, invert);
        this.unit = unit;
        this.invert = invert;
        this.loopWhenOn = !invert && unit instanceof RadarUnitSwitchableAdapter;
        this.lastState = unit.isOn();
        AnimationContext animContext = new AnimationContext(animation.getSpecifiedEndTimeS());
        this.runner = new AnimationRunner(animation, animContext);

        boolean effectiveState = invert ? !unit.isOn() : unit.isOn();
        animContext.setProgress(effectiveState ? animation.getSpecifiedEndTimeS() : 0.0f);
        this.runner.setState(new PauseState());
    }

    @Override
    public void tick() {
        runner.tick();
        if (unit == null) {
            return;
        }
        if (unit.isOn() != lastState) {
            AbstractVehicle vehicle = unit.getVehicle();
            vehicle.level().playSound(LocalVehiclePlayer.instance.getPlayer(), vehicle.blockPosition(),
                    unit.isOn() ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE,
                    SoundSource.PLAYERS, 1f, 1f);
            float speed = unit.isOn() ? 1.0f : -1.0f;
            if (invert) {
                speed = -speed;
            }
            PlayingState playingState = new PlayingState(System::nanoTime, PauseState::new);
            playingState.setSpeed(speed);
            runner.setState(playingState);
            lastState = unit.isOn();
            return;
        }
        if (loopWhenOn && unit.isOn() && runner.getState() instanceof PauseState) {
            ((AnimationContext) runner.getAnimationContext()).setProgress(0.0f);
            runner.setState(new LoopingState(System::nanoTime));
        }
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
