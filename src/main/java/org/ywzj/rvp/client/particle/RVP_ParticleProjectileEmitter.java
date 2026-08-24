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

    /** 判定主体是否确实移动的最小距离平方，避免静止位置反复堆积尾迹。 */
    private static final double MIN_MOVEMENT_SQR = 1.0E-12D;
    /** 每个客户端实体的上一主体位置；弱引用保证实体移除后自动清理。 */
    private static final Map<RVP_BaseBullet, TrailState> TRAIL_STATES = new WeakHashMap<>();

    private RVP_ParticleProjectileEmitter() {}

    public static void tick(RVP_BaseBullet projectile) {
        if (projectile == null || !projectile.level().isClientSide()
                || !projectile.isAlive() || !projectile.isParticleProjectileVisual()) {
            return;
        }
        // 调用本项目弹体配置读取接口：取得当前武器的纯粒子主体与尾迹参数。
        RVP_ParticleProjectileData data = projectile.getParticleProjectileData();
        ResourceLocation particleType = ResourceLocation.tryParse(data.getParticleType());
        if (!data.isEnabled() || !RVP_ParticleIds.WHITE_PHOSPHORUS.equals(particleType)
                || !(projectile.level() instanceof ClientLevel level)) {
            return;
        }
        Vec3 current = projectile.position();
        Player player = Minecraft.getInstance().player;
        double distance = player == null ? 0.0D : player.distanceTo(projectile);
        float flicker = data.getBodyFlicker();
        float bodyScale = Math.max(data.getBodyScale()
                * (1.0f + (level.random.nextFloat() * 2.0f - 1.0f) * flicker), 0.0f);
        boolean bodySpawned = distance <= 512.0D || projectile.tickCount % 2 == 0;

        if (bodySpawned) {
            // 调用本项目白磷粒子工厂：在权威同步位置创建本 Tick 主体火点。
            add(RVP_WhitePhosphorusParticle.createBody(level, current, bodyScale,
                    data.getBodyColorRgb(), data.getBodyLifetimeTicks(), data.isFullBright()));
        }

        TrailState previous = TRAIL_STATES.put(
                projectile, new TrailState(current, projectile.tickCount, bodyScale, bodySpawned));
        if (!data.isTrailEnabled() || distance > 512.0D || previous == null
                || previous.tick() != projectile.tickCount - 1 || !previous.bodySpawned()) {
            return;
        }
        if (current.distanceToSqr(previous.position()) <= MIN_MOVEMENT_SQR) {
            return;
        }
        // 调用本项目白磷粒子工厂：把上一 Tick 的主体位置沉积为唯一历史尾迹点。
        add(RVP_WhitePhosphorusParticle.createTrail(level, previous.position(),
                previous.bodyScale(), data.getTrailEndScale(),
                data.getTrailStartAlpha(), data.getTrailEndAlpha(),
                data.getTrailStartColorRgb(), data.getTrailEndColorRgb(),
                data.getTrailLifetimeTicks(), data.isFullBright()));
    }

    private static void add(Particle particle) {
        if (particle != null) {
            // 调用原版客户端粒子引擎：粒子仅在本地生成，不产生服务端广播包。
            Minecraft.getInstance().particleEngine.add(particle);
        }
    }

    /** 上一客户端 Tick 的主体位置、实际出生尺寸和是否真正生成主体的采样状态。 */
    private record TrailState(Vec3 position, int tick, float bodyScale, boolean bodySpawned) {}
}
