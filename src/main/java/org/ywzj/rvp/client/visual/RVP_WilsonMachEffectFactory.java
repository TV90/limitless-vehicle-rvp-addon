package org.ywzj.rvp.client.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveRenderer;
import java.util.Optional;

/** 由 visual_effect_data.effect_type 选择的新效果，不依赖具体武器名称。 */
public final class RVP_WilsonMachEffectFactory implements RVP_ClientVisualEffectFactory {
    /** 客户端工厂注册 ID。 */
    public static final ResourceLocation EFFECT_TYPE = ResourceLocation.fromNamespaceAndPath("rvp", "wilson_mach_shockwave");

    @Override
    public Optional<RVP_ClientVisualEffect> create(ClientLevel level, RVP_VisualEffectEvent event) {
        int duration = event.durationTicks() < 0 ? 220 : event.durationTicks();
        long elapsed = Math.max(0, level.getGameTime() - event.startGameTime());
        // 调用会话参数快照，让同一次爆炸的三个组件使用同一组固定参数。
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(event.baseExplosionRadius(), event.scale());
        // 调用对应寿命聚合，自动追加/本地预览无云团和反馈时按快速前沿寿命回收。
        int totalDuration = event.density() > 0 || event.flash() || event.shake()
                ? tuning.totalDuration(duration) : tuning.frontDuration(duration);
        if (event.shockwaveProfile() == null || duration == 0 || event.scale() == 0) {
            return Optional.empty();
        }
        // 调用独立震动传播登记，视觉到期、预算淘汰或网络迟到都不能提前截断新增外圈。
        RVP_ShockwaveRenderer.spawnScreenShake(event, duration, tuning);
        if (elapsed >= totalDuration) { return Optional.empty(); }
        // 调用实例构造器补算迟到的模拟，不从零重播闪光。
        return Optional.of(new RVP_WilsonMachEffectInstance(level, event, duration, (int) elapsed, tuning));
    }
}
