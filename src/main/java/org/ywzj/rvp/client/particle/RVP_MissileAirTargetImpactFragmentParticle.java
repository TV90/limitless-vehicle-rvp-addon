package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.impact.RVP_MissileAirTargetImpactFragmentMath;

/**
 * 导弹命中离地空中目标后的客户端纯视觉碎片。
 *
 * <p>碎片沿导弹爆炸时刻速度前进，三轴速度使用统一阻尼，并叠加轻微随机布朗扰动；
 * 速度低于停止阈值后移除。白烟复用 {@link RVP_WhitePhosphorusParticle} 的
 * {@code nuclear/particle_base.png} 软圆斑，不增加资源文件。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_MissileAirTargetImpactFragmentParticle extends SingleQuadParticle {
    /** 碎片使用的已有径向光斑渲染类型。 */
    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, RVP_MOD.modLocation("textures/nuclear/flare.png"));
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator tesselator) {
            tesselator.end();
            RenderSystem.depthMask(true);
        }

        @Override
        public String toString() {
            return "RVP_MISSILE_AIR_TARGET_IMPACT_FRAGMENT";
        }
    };

    /** 每 tick 速度保留比例。 */
    private final float damping;
    /** 视为速度归零的阈值，单位格/tick。 */
    private final float stopSpeed;
    /** 布朗运动随机速度扰动，单位格/tick。 */
    private final float brownianStrength;
    /** 白烟生成间隔，单位 tick。 */
    private final int smokeInterval;
    /** 白烟尺寸倍率。 */
    private final float smokeSize;
    /** 白烟寿命，单位 tick。 */
    private final int smokeLifetime;
    /** 白烟粒子出生透明度。 */
    private final float smokeStartAlpha;
    /** 白烟粒子寿命结束透明度。 */
    private final float smokeEndAlpha;
    /** 每个轨迹采样点叠加的白烟贴图层数。 */
    private final int smokeLayers;
    /** 白烟轨迹采样点最大间距，单位格。 */
    private final float smokePointSpacing;
    /** 白烟贴图随机旋转范围，单位度。 */
    private final float smokeRotationDegrees;
    /** 白烟层间相对尺寸随机差异。 */
    private final float smokeLayerScaleVariance;
    /** 单个碎片每次白烟生成最多补出的轨迹点数。 */
    private final int smokeMaxPointsPerTick;
    /** 碎片异常寿命安全上限，单位 tick。 */
    private final int maxLifetime;
    /** 当前粒子的确定性随机源，用于布朗扰动和少量视觉差异。 */
    private final RandomSource random;
    /** 碎片初始面片半宽，单位格。 */
    private final float initialSize;
    /** 本视觉粒子是否已因速度归零或安全寿命到期而结束。 */
    private boolean visualExpired;
    /** 上一次实际生成白烟时的碎片位置，用于跨 tick 按距离补齐轨迹。 */
    private Vec3 lastSmokePosition;

    private RVP_MissileAirTargetImpactFragmentParticle(
            ClientLevel level,
            double x,
            double y,
            double z,
            double velocityX,
            double velocityY,
            double velocityZ,
            float damping,
            float stopSpeed,
            float brownianStrength,
            int smokeInterval,
            float smokeSize,
            int smokeLifetime,
            float smokeStartAlpha,
            float smokeEndAlpha,
            int smokeLayers,
            float smokePointSpacing,
            float smokeRotationDegrees,
            float smokeLayerScaleVariance,
            int smokeMaxPointsPerTick,
            int maxLifetime,
            long seed) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.damping = damping;
        this.stopSpeed = stopSpeed;
        this.brownianStrength = brownianStrength;
        this.smokeInterval = smokeInterval;
        this.smokeSize = smokeSize;
        this.smokeLifetime = smokeLifetime;
        this.smokeStartAlpha = smokeStartAlpha;
        this.smokeEndAlpha = smokeEndAlpha;
        this.smokeLayers = smokeLayers;
        this.smokePointSpacing = smokePointSpacing;
        this.smokeRotationDegrees = smokeRotationDegrees;
        this.smokeLayerScaleVariance = smokeLayerScaleVariance;
        this.smokeMaxPointsPerTick = smokeMaxPointsPerTick;
        this.maxLifetime = maxLifetime;
        this.random = RandomSource.create(seed);
        this.initialSize = 0.055F + random.nextFloat() * 0.025F;
        this.quadSize = this.initialSize;
        this.xd = velocityX;
        this.yd = velocityY;
        this.zd = velocityZ;
        this.rCol = 0.90F + random.nextFloat() * 0.10F;
        this.gCol = 0.92F + random.nextFloat() * 0.08F;
        this.bCol = 0.96F + random.nextFloat() * 0.04F;
        this.alpha = 0.92F;
        this.roll = random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.hasPhysics = false;
        this.lastSmokePosition = new Vec3(x, y, z);
    }

    /** 创建一枚沿命中时速度飞行的客户端视觉碎片。 */
    public static RVP_MissileAirTargetImpactFragmentParticle create(
            ClientLevel level,
            Vec3 position,
            Vec3 velocity,
            float damping,
            float stopSpeed,
            float brownianStrength,
            int smokeInterval,
            float smokeSize,
            int smokeLifetime,
            float smokeStartAlpha,
            float smokeEndAlpha,
            int smokeLayers,
            float smokePointSpacing,
            float smokeRotationDegrees,
            float smokeLayerScaleVariance,
            int smokeMaxPointsPerTick,
            int maxLifetime,
            long seed) {
        return new RVP_MissileAirTargetImpactFragmentParticle(
                level, position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z,
                damping, stopSpeed, brownianStrength,
                smokeInterval, smokeSize, smokeLifetime, smokeStartAlpha, smokeEndAlpha,
                smokeLayers, smokePointSpacing,
                smokeRotationDegrees, smokeLayerScaleVariance, smokeMaxPointsPerTick,
                maxLifetime, seed);
    }

    /** 在网络消息延迟期间无烟追赶粒子运动，保持视觉位置接近服务端事件时刻。 */
    public void catchUpWithoutSmoke(int ticks) {
        int safeTicks = Math.min(Math.max(ticks, 0), maxLifetime);
        for (int index = 0; index < safeTicks && !isVisualExpired(); index++) {
            tickInternal(false);
        }
    }

    /** 返回本项目速度规则判定的视觉粒子生命周期状态。 */
    public boolean isVisualExpired() {
        return visualExpired;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    public int getLightColor(float partialTick) {
        // 碎片使用全亮光斑，避免高速小面片在夜间因环境光过暗而消失。
        return 0xF000F0;
    }

    @Override
    protected float getU0() {
        return 0.0F;
    }

    @Override
    protected float getU1() {
        return 1.0F;
    }

    @Override
    protected float getV0() {
        return 0.0F;
    }

    @Override
    protected float getV1() {
        return 1.0F;
    }

    @Override
    public void tick() {
        tickInternal(true);
    }

    /** 推进一 tick，并按需生成拖尾白烟。 */
    private void tickInternal(boolean emitSmoke) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;

        Vec3 currentVelocity = new Vec3(this.xd, this.yd, this.zd);
        if (!Double.isFinite(currentVelocity.x) || !Double.isFinite(currentVelocity.y)
                || !Double.isFinite(currentVelocity.z)
                || currentVelocity.lengthSqr() <= (double) stopSpeed * stopSpeed) {
            expireVisual();
            return;
        }
        this.move(this.xd, this.yd, this.zd);
        Vec3 currentPosition = new Vec3(this.x, this.y, this.z);
        if (!emitSmoke) {
            // 追赶网络延迟时同步白烟参考点，避免把已经过去的追赶路段一次性补成烟雾。
            this.lastSmokePosition = currentPosition;
        } else if (this.age % smokeInterval == 0) {
            spawnSmokeAlongSegment(this.lastSmokePosition, currentPosition);
            this.lastSmokePosition = currentPosition;
        }

        Vec3 nextVelocity = currentVelocity.scale(damping);
        double brownianGate = Math.max(stopSpeed * 4.0D, 0.001D);
        if (nextVelocity.lengthSqr() > brownianGate * brownianGate && brownianStrength > 0.0F) {
            // 调用客户端随机源叠加三轴轻微布朗扰动，保持主行进方向由继承速度决定。
            nextVelocity = nextVelocity.add(
                    random.nextGaussian() * brownianStrength,
                    random.nextGaussian() * brownianStrength,
                    random.nextGaussian() * brownianStrength);
        }
        if (nextVelocity.lengthSqr() <= (double) stopSpeed * stopSpeed) {
            expireVisual();
            return;
        }
        this.xd = nextVelocity.x;
        this.yd = nextVelocity.y;
        this.zd = nextVelocity.z;
        this.roll += (random.nextFloat() - 0.5F) * 0.15F;
        ++this.age;
        float progress = Mth.clamp((float) this.age / Math.max(1, maxLifetime), 0.0F, 1.0F);
        this.quadSize = this.initialSize * (1.0F - progress * 0.35F);
        this.alpha = 0.92F * (1.0F - progress * 0.35F);
        if (this.age >= maxLifetime) {
            expireVisual();
        }
    }

    /** 标记视觉粒子结束并通知 Minecraft 粒子引擎移除它。 */
    private void expireVisual() {
        visualExpired = true;
        remove();
    }

    /** 按碎片实际移动线段补齐白烟采样点，避免高速碎片每 tick 只放一枚烟造成断档。 */
    private void spawnSmokeAlongSegment(Vec3 start, Vec3 end) {
        Vec3 safeStart = start == null ? new Vec3(this.xo, this.yo, this.zo) : start;
        Vec3 delta = end.subtract(safeStart);
        double segmentLength = delta.length();
        // 调用本项目无状态数学辅助类，按本次线段长度计算需要补出的白烟采样点数。
        int pointCount = RVP_MissileAirTargetImpactFragmentMath.resolveSmokePointCount(
                segmentLength, smokePointSpacing, smokeMaxPointsPerTick);
        for (int index = 0; index < pointCount; index++) {
            // 使用每个子区间中点，让补出的烟均匀覆盖从上一次生成点到当前点的整段路径。
            double interpolation = (index + 0.5D) / pointCount;
            spawnSmokeLayers(safeStart.add(delta.scale(interpolation)));
        }
    }

    /** 在一个轨迹采样点叠加多层白烟贴图，随机旋转和尺寸差异用于表现烟团体积。 */
    private void spawnSmokeLayers(Vec3 position) {
        float rotationRadians = smokeRotationDegrees * ((float) Math.PI / 180.0F);
        for (int layer = 0; layer < smokeLayers; layer++) {
            float scaleVariance = (random.nextFloat() * 2.0F - 1.0F) * smokeLayerScaleVariance;
            float layerSize = smokeSize * Math.max(0.25F, 1.0F + scaleVariance);
            float startSize = 0.055F * layerSize;
            float endSize = 0.18F * layerSize;
            // 调用本项目白磷粒子工厂：其渲染类型直接绑定 nuclear/particle_base.png。
            RVP_WhitePhosphorusParticle smoke = RVP_WhitePhosphorusParticle.createTrail(
                    this.level, position, startSize, endSize,
                    smokeStartAlpha, smokeEndAlpha, 0xFFFFFF, 0xFFFFFF,
                    0, 0, smokeLifetime, true, null)
                    .withRollRadians(random.nextFloat() * rotationRadians);
            // 调用 Minecraft 客户端粒子引擎加入白烟拖尾；该粒子只影响客户端画面。
            Minecraft.getInstance().particleEngine.add(smoke);
        }
    }
}
