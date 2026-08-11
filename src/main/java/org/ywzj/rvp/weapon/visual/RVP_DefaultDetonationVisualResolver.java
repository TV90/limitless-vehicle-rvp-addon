package org.ywzj.rvp.weapon.visual;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.data.RVP_VisualEffectData;
import org.ywzj.rvp.weapon.visual.api.RVP_DetonationVisualContext;
import org.ywzj.rvp.weapon.visual.api.RVP_DetonationVisualResolver;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Optional;

/** 首版通用解析器：只校验公共字段，不绑定任何具体温压客户端实现。 */
public final class RVP_DefaultDetonationVisualResolver implements RVP_DetonationVisualResolver {
    @Override
    public boolean supports(RVP_VisualEffectData data) {
        return data != null && data.isEnabled() && data.getEffectType().isPresent();
    }

    @Override
    public Optional<RVP_VisualEffectEvent> resolve(
            RVP_DetonationVisualContext context,
            RVP_VisualEffectData data) {
        if (!supports(data) || context == null || !Float.isFinite(context.baseExplosionRadius())
                || context.baseExplosionRadius() < 0.0F) {
            return Optional.empty();
        }
        Optional<String> canonicalPresetData = RVP_VisualPresetDataCodec.canonicalize(data.copyPresetData());
        if (canonicalPresetData.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation effectType = data.getEffectType().orElseThrow();
        return Optional.of(new RVP_VisualEffectEvent(
                effectType,
                data.getPreset(),
                canonicalPresetData.get(),
                context.level().dimension(),
                context.position(),
                context.baseExplosionRadius(),
                data.getScale(),
                data.getDensity(),
                data.getDurationTicks(),
                data.getBroadcastRange(),
                context.level().random.nextLong(),
                context.startGameTime(),
                data.isSound(),
                data.isFlash(),
                data.isShake()));
    }
}
