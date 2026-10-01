package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;

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
        this.frame = 2;
        this.initialSize = Math.max(0.15f, size);
        this.quadSize = this.initialSize;
        // 调用本项目调试参数：长程烟出生时读取当前东向风。
        this.xd = Parameter.EAST_WIND.value() + vx;
        this.yd = vy;
        this.zd = vz;
        this.breezePhase = level.random.nextDouble() * Mth.TWO_PI;
        this.hasPhysics = false;
        float shade = layer == SmokeLayer.LONG_OUTER
                ? 0.18f + level.random.nextFloat() * 0.10f
                : 0.08f + level.random.nextFloat() * 0.09f;
        this.rCol = shade;
        this.gCol = shade + 0.005f;
        this.bCol = shade + 0.015f;
        float baseAlpha = layer == SmokeLayer.LONG_OUTER
                ? 0.28f + level.random.nextFloat() * 0.12f
                : 0.66f + level.random.nextFloat() * 0.18f;
        this.initialAlpha = Mth.clamp(baseAlpha * (layer == SmokeLayer.LONG_CORE
                ? Parameter.CORE_ALPHA_SCALE.floatValue() : Parameter.OUTER_ALPHA_SCALE.floatValue()),
                0.0f, 1.0f);
        this.alpha = this.initialAlpha;
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.rollSpeed = (level.random.nextFloat() - 0.5f) * 0.025f;
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
        // 长程烟保持天空光全亮，避免夜间烟柱被环境光完全吞掉。
        return 15 << 20;
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
        float progress = (float) this.age / this.lifetime;
        double rise = Math.max(0.0, this.y - this.spawnY);
        int formationTicks = Parameter.FORMATION_TICKS.intValue();
        if (this.age < formationTicks) {
            this.frame = 2 - Math.min(2, this.age * 3 / formationTicks);
        } else {
            this.frame = 0;
        }
        this.quadSize = this.initialSize * (1.0f + (float) rise * Parameter.SIZE_GAIN.floatValue());
        float fadeStart = Parameter.FADE_START.floatValue();
        this.alpha = this.initialAlpha * (progress < fadeStart ? 1.0f
                : (1.0f - progress) / (1.0f - fadeStart));
        this.roll += this.rollSpeed;
        double breezeWave = this.age * 0.06 + this.breezePhase;
        double windGain = Parameter.WIND_GAIN.value();
        double sway = Parameter.SWAY.value();
        double targetEastSpeed = Parameter.EAST_WIND.value() * (1.0 + rise * windGain);
        this.xd = targetEastSpeed + (this.xd - targetEastSpeed) * 0.96
                + Math.sin(breezeWave) * sway;
        this.zd = this.zd * 0.96 + Math.cos(breezeWave) * sway;
        this.yd = Parameter.UPDRAFT.value() + (this.yd - Parameter.UPDRAFT.value()) * 0.98;
        // 调用本类粒子基类位移：保持长程烟的上升、东风和微摆运动。
        this.move(this.xd, this.yd, this.zd);
    }
}
