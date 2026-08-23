package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;

/**
 * 直接使用 {@code assets/ywzj_rvp/textures/nuclear/particle_base.png}，
 * 实现短寿命主体和沿年龄缩小、变淡的白磷尾迹，不依赖粒子图集追加文件。
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WhitePhosphorusParticle extends SingleQuadParticle {

    /** 白磷主体与尾迹直接绑定的权威纹理。 */
    private static final ResourceLocation PARTICLE_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/particle_base.png");

    /** 白磷粒子的独立半透明渲染类型，使用完整纹理 UV。 */
    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            RenderSystem.depthMask(true);
            RenderSystem.setShaderTexture(0, PARTICLE_TEXTURE);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator tesselator) {
            tesselator.end();
        }

        @Override
        public String toString() {
            return "RVP_WHITE_PHOSPHORUS";
        }
    };

    /** 是否为保持尺寸稳定的主体粒子。 */
    private final boolean body;
    /** 出生尺寸倍率。 */
    private final float startScale;
    /** 消失尺寸倍率。 */
    private final float endScale;
    /** 出生透明度。 */
    private final float startAlpha;
    /** 消失透明度。 */
    private final float endAlpha;
    /** 出生 RGB 颜色。 */
    private final int startColor;
    /** 消失 RGB 颜色。 */
    private final int endColor;
    /** 是否使用全亮光照。 */
    private final boolean fullBright;

    private RVP_WhitePhosphorusParticle(ClientLevel level, Vec3 position, boolean body,
                                        float startScale, float endScale,
                                        float startAlpha, float endAlpha,
                                        int startColor, int endColor,
                                        int lifetime, boolean fullBright) {
        super(level, position.x, position.y, position.z);
        this.body = body;
        this.startScale = startScale;
        this.endScale = endScale;
        this.startAlpha = startAlpha;
        this.endAlpha = endAlpha;
        this.startColor = startColor;
        this.endColor = endColor;
        this.fullBright = fullBright;
        this.lifetime = Math.max(lifetime, 1);
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
        applyCurve(0.0f);
    }

    public static Particle createBody(ClientLevel level, Vec3 position, float scale, int color,
                                      int lifetime, boolean fullBright) {
        return new RVP_WhitePhosphorusParticle(
                level, position, true, scale, scale, 1.0f, 1.0f,
                color, color, lifetime, fullBright);
    }

    public static Particle createTrail(ClientLevel level, Vec3 position,
                                       float startScale, float endScale,
                                       float startAlpha, float endAlpha,
                                       int startColor, int endColor,
                                       int lifetime, boolean fullBright) {
        return new RVP_WhitePhosphorusParticle(
                level, position, false, startScale, endScale, startAlpha, endAlpha,
                startColor, endColor, lifetime, fullBright);
    }

    @Override
    public void tick() {
        super.tick();
        if (!removed) {
            float progress = Mth.clamp((float) age / Math.max(lifetime, 1), 0.0f, 1.0f);
            applyCurve(progress);
        }
    }

    private void applyCurve(float progress) {
        float curve = body ? 0.0f : progress * progress * (3.0f - 2.0f * progress);
        quadSize = Mth.lerp(curve, startScale, endScale);
        alpha = Mth.lerp(curve, startAlpha, endAlpha);
        rCol = Mth.lerp(progress, red(startColor), red(endColor));
        gCol = Mth.lerp(progress, green(startColor), green(endColor));
        bCol = Mth.lerp(progress, blue(startColor), blue(endColor));
    }

    private static float red(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255.0f;
    }

    private static float green(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255.0f;
    }

    private static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0f;
    }

    @Override
    public int getLightColor(float partialTick) {
        return fullBright ? 0x00F000F0 : super.getLightColor(partialTick);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    protected float getU0() {
        return 0.0f;
    }

    @Override
    protected float getU1() {
        return 1.0f;
    }

    @Override
    protected float getV0() {
        return 0.0f;
    }

    @Override
    protected float getV1() {
        return 1.0f;
    }

    /** Forge 非 JSON 粒子 Provider；普通 addParticle 调用时生成默认主体。 */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new RVP_WhitePhosphorusParticle(level, new Vec3(x, y, z), true,
                    0.4f, 0.4f, 1.0f, 1.0f,
                    0xFFC247, 0xFFC247, 3, true);
        }
    }
}
