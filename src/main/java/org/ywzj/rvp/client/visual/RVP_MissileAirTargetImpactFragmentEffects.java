package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.particle.RVP_MissileAirTargetImpactFragmentParticle;
import org.ywzj.rvp.network.S2CMissileAirTargetImpactFragments;
import org.ywzj.rvp.weapon.impact.RVP_MissileAirTargetImpactFragmentMath;

/** 客户端创建导弹空中目标命中视觉碎片的入口。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_MissileAirTargetImpactFragmentEffects {
    private RVP_MissileAirTargetImpactFragmentEffects() {
    }

    /** 接收服务端事件并创建本地碎片与拖尾白烟。 */
    public static void accept(S2CMissileAirTargetImpactFragments message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().location().equals(message.dimension())) {
            return;
        }
        Vec3 center = new Vec3(message.x(), message.y(), message.z());
        Vec3 velocity = new Vec3(message.velocityX(), message.velocityY(), message.velocityZ());
        double fragmentSpeed = velocity.length();
        RVP_MissileAirTargetImpactFragmentMath.OrthonormalBasis basis =
                RVP_MissileAirTargetImpactFragmentMath.perpendicularBasis(velocity);
        RandomSource random = RandomSource.create(message.seed());
        long elapsed = level.getGameTime() - message.startGameTime();
        int catchUpTicks = (int) Math.max(0L, Math.min((long) message.maxLifetime(), elapsed));
        for (int index = 0; index < message.count(); index++) {
            double angle = (Math.PI * 2.0D * index / message.count())
                    + random.nextDouble() * Math.PI * 2.0D;
            double radius = message.spawnSpread() * (0.65D + random.nextDouble() * 0.35D);
            Vec3 offset = basis.first().scale(Math.cos(angle) * radius)
                    .add(basis.second().scale(Math.sin(angle) * radius));
            Vec3 position = center.add(offset);
            // 调用本项目数学辅助类，在导弹命中方向周围的圆锥内随机采样碎片方向。
            Vec3 fragmentDirection = RVP_MissileAirTargetImpactFragmentMath.randomDirectionInCone(
                    velocity,
                    Math.toRadians(message.fragmentConeHalfAngleDegrees()),
                    random);
            Vec3 fragmentVelocity = fragmentDirection.scale(fragmentSpeed);
            RVP_MissileAirTargetImpactFragmentParticle fragment =
                    RVP_MissileAirTargetImpactFragmentParticle.create(
                            level, position, fragmentVelocity,
                            message.damping(), message.stopSpeed(), message.brownianStrength(),
                            message.smokeInterval(), message.smokeSize(), message.smokeLifetime(),
                            message.smokeStartAlpha(), message.smokeEndAlpha(),
                            message.smokeLayers(), message.smokePointSpacing(),
                            message.smokeRotationDegrees(), message.smokeLayerScaleVariance(),
                            message.smokeMaxPointsPerTick(),
                            message.maxLifetime(), random.nextLong());
            fragment.catchUpWithoutSmoke(catchUpTicks);
            if (!fragment.isVisualExpired()) {
                // 调用 Minecraft 客户端粒子引擎加入纯视觉碎片，不创建实体、不参与伤害。
                minecraft.particleEngine.add(fragment);
            }
        }
    }
}
