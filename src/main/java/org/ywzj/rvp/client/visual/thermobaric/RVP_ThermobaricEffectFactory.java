package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.visual.RVP_ClientVisualEffect;
import org.ywzj.rvp.client.visual.RVP_ClientVisualEffectFactory;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Optional;

/** 把 {@code rvp:thermobaric} 领域事件解析成客户端温压视觉实例。 */
public final class RVP_ThermobaricEffectFactory implements RVP_ClientVisualEffectFactory {
    /** 通用视觉协议中的温压效果类型。 */
    public static final ResourceLocation EFFECT_TYPE =
            ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric");

    @Override
    public Optional<RVP_ClientVisualEffect> create(ClientLevel level, RVP_VisualEffectEvent event) {
        // 调用温压预设管理器，按“内建默认值 < 武器 preset_data”合并并校验类型化字段。
        RVP_ThermobaricPreset preset = RVP_ThermobaricPresetManager.resolve(
                event.preset(), event.canonicalPresetDataJson());
        int duration = event.durationTicks() >= 0
                ? event.durationTicks()
                : preset.effectEndTick();
        long elapsed = Math.max(0L, level.getGameTime() - event.startGameTime());
        if (elapsed >= duration) {
            return Optional.empty();
        }
        return Optional.of(new RVP_ThermobaricEffectInstance(
                level, event, preset, duration, (int) elapsed));
    }
}
