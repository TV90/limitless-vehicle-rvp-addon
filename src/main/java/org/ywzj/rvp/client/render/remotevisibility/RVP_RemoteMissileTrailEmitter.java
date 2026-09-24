package org.ywzj.rvp.client.render.remotevisibility;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_RocketFlameParticle;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;

import javax.annotation.Nullable;

/** 根据 RVP 武器效果配置，为超视距弹药克隆生成与近距一致的风格化飞行尾迹粒子。 */
final class RVP_RemoteMissileTrailEmitter {
    private RVP_RemoteMissileTrailEmitter() {
    }

    /**
     * 在一个远程路径采样点生成尾迹；配置缺失时保持旧行为，回退为原版信号烟。
     *
     * @param minecraft 客户端实例
     * @param effects RVP 导弹效果配置；本体导弹或配置尚未加载时为 null
     * @param position 粒子世界坐标
     * @param exhaustVelocity 沿弹轴反方向的尾喷初速
     * @param remainingBurnTicks 服务端同步的距最后燃尽剩余 Tick
     */
    static void spawn(Minecraft minecraft, @Nullable RVP_EffectsData effects, Vec3 position,
                      Vec3 exhaustVelocity, int remainingBurnTicks) {
        if (minecraft.level == null) {
            return;
        }
        if (effects == null) {
            minecraft.level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                    position.x, position.y, position.z, 0.0D, 0.0D, 0.0D);
            return;
        }

        // 调用 RVP 效果数据解析出口，让远距粒子尺寸与近距 native missile trail 使用同一配置。
        float scale = effects.getMissileNativeTrailParticleScale();
        if (effects.isMissileNativeTrailRocketFlame()) {
            // 调用超视距专用 HBM 工厂，保留固体橙焰→凝结云曲线并放宽 1024 格消亡边界。
            minecraft.particleEngine.add(RVP_RocketFlameParticle.ofRemoteTrail(
                    minecraft.level, position.x, position.y, position.z,
                    exhaustVelocity.x, exhaustVelocity.y, exhaustVelocity.z,
                    scale, remainingBurnTicks));
            return;
        }
        if (effects.isMissileNativeTrailKeroseneBlackSmoke()) {
            // 调用现有 HBM 煤油黑烟工厂；其本身无 1024 格凝结云消亡限制且远距自动单层渲染。
            minecraft.particleEngine.add(RVP_RocketFlameParticle.ofKeroseneBlackSmokeTrail(
                    minecraft.level, position.x, position.y, position.z,
                    exhaustVelocity.x, exhaustVelocity.y, exhaustVelocity.z, scale));
            return;
        }
        if (effects.isMissileNativeTrailRvpSmoke()) {
            // 调用现有 MCHR 烟团工厂，使超视距尾迹保留 8 帧翻滚消散与配置尺寸。
            minecraft.particleEngine.add(RVP_MchrSmokeParticle.ofTrailScaled(
                    minecraft.level, position.x, position.y, position.z, scale));
            return;
        }

        ParticleOptions particle = resolveVanillaParticle(
                effects.hasMissileNativeTrailParticleOverride()
                        ? effects.getMissileNativeTrailParticle()
                        : "",
                ParticleTypes.CAMPFIRE_SIGNAL_SMOKE);
        if (particle != null) {
            // 调用 RVP 客户端粒子桥，为原版粒子应用与近距尾迹一致的渲染尺寸倍率。
            RVP_ClientActionsAccess.addScaledParticle(
                    particle, position.x, position.y, position.z, scale);
        }
    }

    /** 判断当前配置是否由自定义风格完整接管尾迹，避免再叠加旧远程原版 FLAME。 */
    static boolean usesCustomStyle(@Nullable RVP_EffectsData effects) {
        if (effects == null) {
            return false;
        }
        // 调用 RVP 风格配置出口，与近距自定义尾迹抑制服务端广播的口径保持一致。
        return effects.hasMissileNativeTrailParticleStyle();
    }

    /** 解析 native missile trail 支持的原版粒子短名；未知值保持既有回退语义。 */
    @Nullable
    private static ParticleOptions resolveVanillaParticle(String id, ParticleOptions fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        return switch (id) {
            case "none", "minecraft:none" -> null;
            case "flame", "minecraft:flame" -> ParticleTypes.FLAME;
            case "large_smoke", "minecraft:large_smoke" -> ParticleTypes.LARGE_SMOKE;
            case "cloud", "minecraft:cloud" -> ParticleTypes.CLOUD;
            case "lava", "minecraft:lava" -> ParticleTypes.LAVA;
            case "campfire_smoke", "minecraft:campfire_cosy_smoke" -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
            case "campfire_signal_smoke", "minecraft:campfire_signal_smoke" -> ParticleTypes.CAMPFIRE_SIGNAL_SMOKE;
            case "explosion", "minecraft:explosion" -> ParticleTypes.EXPLOSION;
            case "explosion_emitter", "minecraft:explosion_emitter" -> ParticleTypes.EXPLOSION_EMITTER;
            case "smoke", "minecraft:smoke" -> ParticleTypes.SMOKE;
            case "block", "minecraft:block" -> fallback;
            default -> fallback;
        };
    }
}
