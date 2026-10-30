package org.ywzj.rvp.weapon.visual;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.weapon.data.RVP_VisualEffectData;
import org.ywzj.rvp.weapon.visual.api.RVP_DetonationVisualContext;
import org.ywzj.rvp.weapon.visual.api.RVP_DetonationVisualResolver;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Optional;
import org.ywzj.rvp.server.visual.RVP_ShockwaveProfileSampler;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;

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
        // 调用现有预设规范化器，灯光由客户端代码推导，不再向预设中追加光源配置。
        Optional<String> canonicalPresetData = RVP_VisualPresetDataCodec.canonicalize(data.copyPresetData());
        if (canonicalPresetData.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation effectType = data.getEffectType().orElseThrow();
        boolean shockwave = RVP_ShockwaveProfileSampler.EFFECT_TYPE.equals(effectType);
        // 在真实爆炸破坏地形前采样；只有本效果类型承担这笔开销。
        RVP_ShockwaveProfile profile = shockwave
                ? RVP_ShockwaveProfileSampler.sample(context.level(), context.position(), context.baseExplosionRadius(), data.getScale())
                : null;
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
                shockwave ? Math.max(8192.0D, data.getBroadcastRange()) : data.getBroadcastRange(),
                context.level().random.nextLong(),
                context.startGameTime(),
                data.isSound(),
                data.isFlash(),
                data.isShake(),
                data.isExperimentalDynamicParticleBudget(), profile));
    }
}
