package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeLogic;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeLogic;

/** 长程击毁烟粒子；近处烟已抽取为可复用的 {@link RVP_NearSmokeParticle}。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckSmokeParticle extends SingleQuadParticle {

    /** 长程烟横条的帧数：boom/smoke.png 为 8 帧横排。 */
    private static final int FRAME_COUNT = 8;
    /** 长程烟核心与外层共用已有的 boom/smoke.png。 */
    private static final ParticleRenderType RENDER_TYPE = renderType("textures/boom/smoke.png");

    /** 长程烟层次：核心和外层使用不同尺寸、颜色与透明度。 */
    public enum SmokeLayer {
        /** 长程烟柱的深色主体。 */
        LONG_CORE,
        /** 长程烟柱较大而透明的外轮廓。 */
        LONG_OUTER
    }

    /** 当前长程烟粒子的渲染类型。 */
    private final ParticleRenderType renderType;
    /** 当前粒子所属的长程烟层。 */
    private final SmokeLayer layer;
    /** 出生时的面片半宽，单位格。 */
    private final float initialSize;
    /** 出生时的透明度，寿命末段逐渐降低。 */
    private final float initialAlpha;
    /** 粒子出生时的世界 Y 坐标。 */
    private final double spawnY;
    /** 当前横条帧索引。 */
    private int frame;
    /** 每 tick 绕视线轴转动的弧度。 */
    private final float rollSpeed;
    /** 每枚烟团独立的微风相位。 */
    private final double breezePhase;

    private RVP_WreckSmokeParticle(ClientLevel level, double x, double y, double z,
                                   double vx, double vy, double vz, float size,
                                   int lifetime, int variant, SmokeLayer layer) {
        super(level, x, y, z);
        this.layer = layer;
        this.renderType = RENDER_TYPE;
        this.lifetime = Math.max(16, lifetime);
        this.spawnY = y;
        // 长程烟从较稀疏的第 3 帧开始，避免生成瞬间闪现完整烟团。
        // 调用共用成团曲线；零成团时间立即用完整帧，默认保持原稀疏出生帧。
        this.frame = RVP_WreckSmokeLogic.longFrame(0, Parameter.FORMATION_TICKS.intValue());
        this.initialSize = Math.max(0.15f, size);
        this.quadSize = this.initialSize;
        // 调用本项目调试参数：长程烟出生时读取当前东向风。
        this.xd = Parameter.EAST_WIND.value() + vx;
        this.yd = vy;
        this.zd = Parameter.LONG_SOUTH_WIND.value() + vz;
        this.breezePhase = level.random.nextDouble() * Mth.TWO_PI;
        this.hasPhysics = false;
        float shade = layer == SmokeLayer.LONG_OUTER
                ? 0.18f + level.random.nextFloat() * 0.10f
                : 0.08f + level.random.nextFloat() * 0.09f;
        // 调用共用染色计算：保留原始随机序列，均值和差异可独立调整。
        shade = RVP_WreckSmokeLogic.grey(shade, layer == SmokeLayer.LONG_OUTER ? 0.23f : 0.125f,
                layer == SmokeLayer.LONG_OUTER ? Parameter.LONG_OUTER_GREY.value() : Parameter.LONG_CORE_GREY.value(),
                Parameter.LONG_GREY_VARIATION.value());
        this.rCol = shade;
        this.gCol = Math.min(1, shade + 0.005f);
        this.bCol = Math.min(1, shade + 0.015f);
        float baseAlpha = layer == SmokeLayer.LONG_OUTER
                ? 0.28f + level.random.nextFloat() * 0.12f
                : 0.66f + level.random.nextFloat() * 0.18f;
        // 调用共用浓度和淡入曲线；出生色与浓度锁定，其后的消散仍实时读取长烟参数。
        this.initialAlpha = RVP_WreckSmokeLogic.alpha(baseAlpha, layer == SmokeLayer.LONG_OUTER ? 0.34f : 0.75f,
                Parameter.LONG_ALPHA_VARIATION.value(), layer == SmokeLayer.LONG_CORE
                        ? Parameter.CORE_ALPHA_SCALE.value() : Parameter.OUTER_ALPHA_SCALE.value());
        this.alpha = this.initialAlpha * RVP_WreckSmokeLogic.alphaMultiplier(
                RVP_WreckSmokeDebugSettings.LIVE, false, 0, this.lifetime, 1);
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.rollSpeed = (level.random.nextFloat() - 0.5f)
                * (float) (Math.toRadians(Parameter.LONG_ROLL_SPEED.value()) / 10);
    }

    /** 创建一枚长程击毁烟粒子；调用方决定层次、位置、寿命和尺寸。 */
    public static RVP_WreckSmokeParticle create(ClientLevel level, double x, double y, double z,
                                                 double vx, double vy, double vz, float size,
                                                 int lifetime, int variant, SmokeLayer layer) {
        return new RVP_WreckSmokeParticle(level, x, y, z, vx, vy, vz,
                size, lifetime, variant, layer);
    }

    /** 按完整贴图路径创建深度只测不写的半透明渲染类型。 */
    private static ParticleRenderType renderType(String texturePath) {
        // 调用本项目资源定位入口：把长程击毁烟贴图绑定到 ywzj_rvp 命名空间。
        ResourceLocation texture = RVP_MOD.modLocation(texturePath);
        return new ParticleRenderType() {
            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                RenderSystem.depthMask(false);
                RenderSystem.setShaderTexture(0, texture);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }

            @Override
            public void end(Tesselator tesselator) {
                tesselator.end();
                RenderSystem.depthMask(true);
            }

            @Override
            public String toString() {
                return "RVP_WRECK_LONG_SMOKE_" + texturePath;
            }
        };
    }

    @Override
    public ParticleRenderType getRenderType() {
        return this.renderType;
    }

    @Override
    public int getLightColor(float partialTick) {
        int original = 15 << 20;
        double mix = Parameter.LONG_ENVIRONMENT_LIGHT_MIX.value();
        if (mix == 0) { return original; }
        // 复用本项目分通道混光，选择性读取烟所在地光照；RVP额外照亮通道保持原入口。
        return RVP_DelayedMuzzleSmokeLogic.blendLight(original,
                LevelRenderer.getLightColor(this.level, BlockPos.containing(this.x, this.y, this.z)), mix);
    }

    @Override
    protected float getU0() {
        return (float) this.frame / FRAME_COUNT;
    }

    @Override
    protected float getU1() {
        return (float) (this.frame + 1) / FRAME_COUNT;
    }

    @Override
    protected float getV0() {
        return 0.0f;
    }

    @Override
    protected float getV1() {
        return 1.0f;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
        ++this.age;
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }
        double rise = Math.max(0.0, this.y - this.spawnY);
        // 调用共用成团与消散曲线，原长烟实时参数下一刻即可作用于已有烟团。
        this.frame = RVP_WreckSmokeLogic.longFrame(this.age, Parameter.FORMATION_TICKS.intValue());
        float sizeMultiplier = 1.0f + (float) rise * Parameter.SIZE_GAIN.floatValue();
        this.quadSize = this.initialSize * sizeMultiplier;
        this.alpha = this.initialAlpha * RVP_WreckSmokeLogic.alphaMultiplier(
                RVP_WreckSmokeDebugSettings.LIVE, false, this.age, this.lifetime, sizeMultiplier);
        this.roll += this.rollSpeed;
        // 调用共用气流步进；默认仍为原有上升、东风和微摆，可选连续翻卷不增加贴图。
        Vec3 velocity = RVP_WreckSmokeLogic.velocityStep(RVP_WreckSmokeDebugSettings.LIVE, false,
                new Vec3(this.xd, this.yd, this.zd), new Vec3(this.x, this.y, this.z),
                this.level.getGameTime(), this.age, this.breezePhase, rise);
        this.xd = velocity.x;
        this.yd = velocity.y;
        this.zd = velocity.z;
        // 调用本类粒子基类位移：保持长程烟的上升、东风和微摆运动。
        this.move(this.xd, this.yd, this.zd);
    }
}
