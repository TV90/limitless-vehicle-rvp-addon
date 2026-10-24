package org.ywzj.rvp.client.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;
import java.util.Optional;

/** 由 visual_effect_data.effect_type 选择的新效果，不依赖具体武器名称。 */
public final class RVP_WilsonMachEffectFactory implements RVP_ClientVisualEffectFactory {
    /** 客户端工厂注册 ID。 */
    public static final ResourceLocation EFFECT_TYPE = ResourceLocation.fromNamespaceAndPath("rvp", "wilson_mach_shockwave");

    @Override
    public Optional<RVP_ClientVisualEffect> create(ClientLevel level, RVP_VisualEffectEvent event) {
        int duration = event.durationTicks() < 0 ? 220 : event.durationTicks();
        long elapsed = Math.max(0, level.getGameTime() - event.startGameTime());
        if (event.shockwaveProfile() == null || duration == 0 || elapsed >= duration || event.scale() == 0) {
            return Optional.empty();
        }
        // 调用实例构造器补算迟到的模拟，不从零重播闪光。
        return Optional.of(new RVP_WilsonMachEffectInstance(level, event, duration, (int) elapsed));
    }
}
