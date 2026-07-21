package org.ywzj.rvp.client.render.animation.runner;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.client.render.animation.runner.SwitchableRunner;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

public final class RVP_SwitchableRunnerFactory {

    private RVP_SwitchableRunnerFactory() {}

    public static SwitchableRunner create(SwitchableUnit<?> unit, BedrockAnimation animation, boolean invert) {
        if (unit instanceof RadarUnitSwitchableAdapter) {
            return new RVP_RadarLoopSwitchableRunner(unit, animation, invert);
        }
        return new SwitchableRunner(unit, animation, invert);
    }
}
