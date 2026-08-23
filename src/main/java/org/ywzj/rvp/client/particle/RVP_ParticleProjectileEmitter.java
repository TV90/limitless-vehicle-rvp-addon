package org.ywzj.rvp.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.all.RVP_ParticleIds;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_ParticleProjectileData;

import java.util.Map;
import java.util.WeakHashMap;

/** 客户端按实体 Tick 生成纯粒子弹体主体和实际历史路径尾迹。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_ParticleProjectileEmitter {

    /** 单实体单 Tick 最大尾迹补点数，避免高速瞬移制造粒子尖峰。 */
    private static final int MAX_TRAIL_SAMPLES_PER_TICK = 12;
    /** 每个客户端实体的上一有效采样点；弱引用保证实体移除后自动清理。 */
    private static final Map<RVP_BaseBullet, TrailState> TRAIL_STATES = new WeakHashMap<>();

    private RVP_ParticleProjectileEmitter() {}

    public static void tick(RVP_BaseBullet projectile) {
        if (projectile == null || !projectile.level().isClientSide()
                || !projectile.isAlive() || !projectile.isParticleProjectileVisual()) {
            return;
        }
        RVP_ParticleProjectileData data = projectile.getParticleProjectileData();
        ResourceLocation particleType = ResourceLocation.tryParse(data.getParticleType());
        if (!data.isEnabled() || !RVP_ParticleIds.WHITE_PHOSPHORUS.equals(particleType)
                || !(projectile.level() instanceof ClientLevel level)) {
            return;
        }
        Vec3 current = projectile.position();
        Player player = Minecraft.getInstance().player;
        double distance = player == null ? 0.0D : player.distanceTo(projectile);

        if (distance <= 512.0D || projectile.tickCount % 2 == 0) {
            float flicker = data.getBodyFlicker();
            float scale = data.getBodyScale()
                    * (1.0f + (level.random.nextFloat() * 2.0f - 1.0f) * flicker);
            add(RVP_WhitePhosphorusParticle.createBody(level, current, Math.max(scale, 0.0f),
                    data.getBodyColorRgb(), data.getBodyLifetimeTicks(), data.isFullBright()));
        }

        TrailState previous = TRAIL_STATES.put(projectile, new TrailState(current, projectile.tickCount));
        if (!data.isTrailEnabled() || distance > 512.0D || previous == null
                || previous.tick() != projectile.tickCount - 1) {
            return;
        }
        Vec3 segment = current.subtract(previous.position());
        double length = segment.length();
        if (length <= 1.0E-6D) {
            return;
        }
        double spacing = data.getTrailSpacing() * lodSpacingMultiplier(distance);
        int samples = Math.min(MAX_TRAIL_SAMPLES_PER_TICK, Math.max(1, (int) Math.ceil(length / spacing)));
        for (int i = 1; i <= samples; i++) {
            Vec3 sample = previous.position().add(segment.scale((double) i / samples));
            add(RVP_WhitePhosphorusParticle.createTrail(level, sample,
                    data.getTrailStartScale(), data.getTrailEndScale(),
                    data.getTrailStartAlpha(), data.getTrailEndAlpha(),
                    data.getTrailStartColorRgb(), data.getTrailEndColorRgb(),
                    data.getTrailLifetimeTicks(), data.isFullBright()));
        }
    }

    private static double lodSpacingMultiplier(double distance) {
        if (distance > 256.0D) {
            return 2.5D;
        }
        if (distance > 128.0D) {
            return 1.5D;
        }
        return 1.0D;
    }

    private static void add(Particle particle) {
        if (particle != null) {
            // 调用原版客户端粒子引擎：粒子仅在本地生成，不产生服务端广播包。
            Minecraft.getInstance().particleEngine.add(particle);
        }
    }

    /** 上一客户端 Tick 的路径采样状态。 */
    private record TrailState(Vec3 position, int tick) {}
}
