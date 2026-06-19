package org.ywzj.rvp.mixin;

import com.maydaymemory.mae.control.runner.AnimationContext;
import com.maydaymemory.mae.control.runner.AnimationRunner;
import com.maydaymemory.mae.control.runner.LoopingState;
import com.maydaymemory.mae.control.runner.PauseState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.client.render.animation.runner.SwitchableRunner;
import org.ywzj.vehicle.vehicle.part.SwitchableUnit;

/**
 * 为搜索雷达（{@code scan_radar}）的连续旋转动画添加循环支持。
 * <p>
 * 仅 {@code RadarUnitSwitchableAdapter} 类型且非 {@code invert} 时启用。
 * 不影响折叠动画（{@code invert=true}）和其他部件（弹舱、起落架等）。
 * </p>
 */
@Mixin(value = SwitchableRunner.class, remap = false)
public class SwitchableRunnerLoopMixin {

    @Shadow(remap = false)
    private SwitchableUnit<?> unit;

    @Shadow(remap = false)
    private AnimationRunner runner;

    @Shadow(remap = false)
    private boolean lastState;

    @Shadow(remap = false)
    private boolean invert;

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void ywzj_rvp$loop(CallbackInfo ci) {
        if (invert) return;
        if (runner == null || unit == null) return;
        // 仅 RadarUnitSwitchableAdapter（搜索雷达适配器）需要循环
        if (!RadarUnitSwitchableAdapter.class.isInstance(unit)) return;
        if (!unit.isOn() || unit.isOn() != lastState) return;

        if (runner.getState() instanceof PauseState) {
            ((AnimationContext) runner.getAnimationContext()).setProgress(0);
            runner.setState(new LoopingState(System::nanoTime));
        }
    }
}
