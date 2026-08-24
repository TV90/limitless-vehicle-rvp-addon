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
    /** 每个客户端实体的上一视觉采样状态；弱引用保证实体移除后自动清理。 */
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
        Vec3 projectilePosition = projectile.position();
        Player player = Minecraft.getInstance().player;
        double distance = player == null ? 0.0D : player.distanceTo(projectile);
        TrailState previous = TRAIL_STATES.get(projectile);
        boolean previousContinuous = previous != null && previous.tick() == projectile.tickCount - 1;
        RVP_TrailLifetimeGate trailLifetimeGate = previous != null
                ? previous.trailLifetimeGate()
                : createTrailLifetimeGate(projectile, data);
        int flickerInterval = data.getBodyFlickerIntervalTicks();
        boolean keepPreviousSample = previousContinuous
                && projectile.tickCount - previous.sample().sampleTick() < flickerInterval;
        FlickerSample sample;
        if (keepPreviousSample) {
            sample = previous.sample();
        } else {
            double startOffsetX = previousContinuous
                    ? previous.bodyPosition().x - previous.projectilePosition().x
                    : 0.0D;
            double startOffsetZ = previousContinuous
                    ? previous.bodyPosition().z - previous.projectilePosition().z
                    : 0.0D;
            sample = createFlickerSample(level, data, projectile.tickCount,
                    startOffsetX, startOffsetZ);
        }
        int transitionElapsedTicks = projectile.tickCount - sample.sampleTick() + 1;
        double offsetX = interpolateHorizontalOffset(sample.startOffsetX(), sample.targetOffsetX(),
                transitionElapsedTicks, flickerInterval);
        double offsetZ = interpolateHorizontalOffset(sample.startOffsetZ(), sample.targetOffsetZ(),
                transitionElapsedTicks, flickerInterval);
        Vec3 bodyPosition = projectilePosition.add(offsetX, 0.0D, offsetZ);
        int bodySampleInterval = data.getBodySampleIntervalTicks();
        int effectiveBodySampleInterval = distance > 512.0D
                ? Math.max(bodySampleInterval, 2)
                : bodySampleInterval;
        boolean bodySpawned = !previousContinuous
                || projectile.tickCount % effectiveBodySampleInterval == 0;

        if (bodySpawned) {
            // 调用本项目白磷粒子工厂：在权威同步位置附近创建沿 X/Z 平滑闪动的本 Tick 主体火点。
            int bodyLifetimeTicks = data.getBodyLifetimeTicks();
            float bodyStartScale = resolveBodyStartScale(
                    data.getBodyStartScale(), sample.bodyScale(), bodyLifetimeTicks);
            add(RVP_WhitePhosphorusParticle.createBody(level, bodyPosition,
                    bodyStartScale, sample.bodyScale(),
                    data.getBodyColorRgb(), data.getBodyEndColorRgb(),
                    bodyLifetimeTicks, data.isFullBright()));
        }

        boolean trailEligible = distance <= 512.0D;
        TRAIL_STATES.put(projectile, new TrailState(projectilePosition, bodyPosition,
                projectile.tickCount, sample.bodyScale(), trailEligible, sample, trailLifetimeGate));
        if (!data.isTrailEnabled() || distance > 512.0D || previous == null
                || previous.tick() != projectile.tickCount - 1 || !previous.trailEligible()) {
            return;
        }
        if (projectilePosition.distanceToSqr(previous.projectilePosition()) <= MIN_MOVEMENT_SQR) {
            return;
        }
        // 调用本项目白磷粒子工厂：把上一 Tick 的实际平滑主体位置沉积为唯一历史尾迹点。
        add(RVP_WhitePhosphorusParticle.createTrail(level, previous.bodyPosition(),
                previous.bodyScale(), data.getTrailEndScale(),
                data.getTrailStartAlpha(), data.getTrailEndAlpha(),
                data.getTrailStartColorRgb(), data.getTrailEndColorRgb(),
                data.getTrailLifetimeTicks(), data.isFullBright(), trailLifetimeGate));
    }

    /** 按当前配置生成一组可跨 Tick 保持的主体尺寸与水平目标点随机样本。 */
    private static FlickerSample createFlickerSample(ClientLevel level,
                                                      RVP_ParticleProjectileData data,
                                                      int sampleTick,
                                                      double startOffsetX,
                                                      double startOffsetZ) {
        // 调用本项目纯粒子配置读取接口：按当前幅度生成可由闪动间隔复用的尺寸和目标位置样本。
        float flicker = data.getBodyFlicker();
        float bodyScale = Math.max(data.getBodyScale()
                * (1.0f + (level.random.nextFloat() * 2.0f - 1.0f) * flicker), 0.0f);
        float horizontalFlicker = data.getBodyHorizontalFlicker();
        double targetOffsetX = horizontalFlicker > 0.0f ? randomSigned(level, horizontalFlicker) : 0.0D;
        double targetOffsetZ = horizontalFlicker > 0.0f ? randomSigned(level, horizontalFlicker) : 0.0D;
        return new FlickerSample(bodyScale, startOffsetX, startOffsetZ,
                targetOffsetX, targetOffsetZ, sampleTick);
    }

    /**
     * 使用平滑步进曲线从上一实际偏移移动到新随机目标，避免目标刷新时直接瞬移。
     *
     * @param startOffset 上一实际单轴偏移，单位格
     * @param targetOffset 本轮随机目标单轴偏移，单位格
     * @param elapsedTicks 本轮已经移动的 Tick 数
     * @param transitionTicks 完成过渡所需 Tick 数
     * @return 当前 Tick 的平滑单轴偏移
     */
    static double interpolateHorizontalOffset(double startOffset, double targetOffset,
                                              int elapsedTicks, int transitionTicks) {
        int safeTransitionTicks = Math.max(transitionTicks, 1);
        double progress = Math.max(0.0D,
                Math.min(elapsedTicks / (double) safeTransitionTicks, 1.0D));
        double smoothProgress = progress * progress * (3.0D - 2.0D * progress);
        return startOffset + (targetOffset - startOffset) * smoothProgress;
    }

    /**
     * 解析主体本次实际出生尺寸；0 关闭生长，大于目标时钳到目标以避免反向缩小。
     *
     * @param configuredStartScale 配置的主体出生尺寸倍率
     * @param targetScale 本次 body_flicker 抽取的目标尺寸倍率
     * @param lifetimeTicks 单个主体寿命，单位 Tick
     * @return 本次主体实际出生尺寸倍率
     */
    static float resolveBodyStartScale(float configuredStartScale, float targetScale,
                                       int lifetimeTicks) {
        if (configuredStartScale <= 0.0f || lifetimeTicks < 2) {
            return targetScale;
        }
        return Math.min(configuredStartScale, targetScale);
    }

    /** 按配置为同一弹体创建共享的落地寿命门；默认关闭时返回 null 并保持原即时计时。 */
    private static RVP_TrailLifetimeGate createTrailLifetimeGate(
            RVP_BaseBullet projectile, RVP_ParticleProjectileData data) {
        // 调用本项目纯粒子配置读取接口：仅为启用落地后计时的尾迹分配共享寿命门。
        return data.isTrailEnabled() && data.isTrailLifetimeStartOnLanding()
                ? new RVP_TrailLifetimeGate(projectile)
                : null;
    }

    /** 在指定幅度内生成单轴均匀随机偏移，供主体 X/Z 闪动共用。 */
    private static double randomSigned(ClientLevel level, float amplitude) {
        return (level.random.nextDouble() * 2.0D - 1.0D) * amplitude;
    }

    private static void add(Particle particle) {
        if (particle != null) {
            // 调用原版客户端粒子引擎：粒子仅在本地生成，不产生服务端广播包。
            Minecraft.getInstance().particleEngine.add(particle);
        }
    }

    /**
     * 上一客户端 Tick 的主体与尾迹采样状态。
     *
     * @param projectilePosition 权威同步弹体位置，仅用于判断实体是否真实移动
     * @param bodyPosition 已叠加水平闪动的主体位置，供下一 Tick 沉积尾迹
     * @param tick 实体采样 Tick，用于拒绝追踪中断后的跨 Tick 连接
     * @param bodyScale 主体本次随机目标尺寸，供对应尾迹继承
     * @param trailEligible 本 Tick 是否处于允许尾迹采样的距离内，避免跨越 512 格边界连接
     * @param sample 当前保持中的尺寸与水平目标点随机样本
     * @param trailLifetimeGate 对应尾迹共享的落地寿命门；null 表示出生后立即计时
     */
    private record TrailState(Vec3 projectilePosition, Vec3 bodyPosition,
                              int tick, float bodyScale, boolean trailEligible,
                              FlickerSample sample, RVP_TrailLifetimeGate trailLifetimeGate) {}

    /**
     * 主体尺寸与水平闪动目标点随机样本。
     *
     * @param bodyScale 主体本次随机目标尺寸
     * @param startOffsetX 本轮开始时相对权威位置的 X 实际偏移，单位格
     * @param startOffsetZ 本轮开始时相对权威位置的 Z 实际偏移，单位格
     * @param targetOffsetX 本轮随机目标点相对权威位置的 X 偏移，单位格
     * @param targetOffsetZ 本轮随机目标点相对权威位置的 Z 偏移，单位格
     * @param sampleTick 本组随机值开始生效的实体 Tick
     */
    private record FlickerSample(float bodyScale,
                                 double startOffsetX, double startOffsetZ,
                                 double targetOffsetX, double targetOffsetZ,
                                 int sampleTick) {}
}
