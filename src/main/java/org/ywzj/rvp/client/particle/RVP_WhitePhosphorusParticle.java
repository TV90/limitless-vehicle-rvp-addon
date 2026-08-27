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
 * 实现沿年龄渐变的短寿命主体和缩小、变淡的白磷尾迹，不依赖粒子图集追加文件。
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

    /** 是否为主体粒子；主体会在最后一个可见 Tick 到达目标尺寸和末端颜色。 */
    private final boolean body;
    /** 出生尺寸倍率。 */
    private final float startScale;
    /** 寿命末端目标尺寸倍率。 */
    private final float endScale;
    /** 出生透明度。 */
    private final float startAlpha;
    /** 消失透明度。 */
    private final float endAlpha;
    /** 出生 RGB 颜色。 */
    private final int startColor;
    /** 消失 RGB 颜色。 */
    private final int endColor;
    /** 尾迹出生后的高温火光阶段时长，单位 Tick；0 表示禁用。 */
    private final int trailHotPhaseTicks;
    /** 尾迹高温阶段出生 RGB 颜色。 */
    private final int trailHotColor;
    /** 是否使用全亮光照。 */
    private final boolean fullBright;
    /** 尾迹共用的落地寿命计时门；null 表示按出生 Tick 立即计时。 */
    private final RVP_TrailLifetimeGate lifetimeGate;
    /** 不受落地寿命门冻结的视觉年龄，保证高温火光在飞行中也会按时冷却。 */
    private int visualAge;

    private RVP_WhitePhosphorusParticle(ClientLevel level, Vec3 position, boolean body,
                                        float startScale, float endScale,
                                        float startAlpha, float endAlpha,
                                        int startColor, int endColor,
                                        int trailHotPhaseTicks, int trailHotColor,
                                        int lifetime, boolean fullBright,
                                        RVP_TrailLifetimeGate lifetimeGate) {
        super(level, position.x, position.y, position.z);
        this.body = body;
        this.startScale = startScale;
        this.endScale = endScale;
        this.startAlpha = startAlpha;
        this.endAlpha = endAlpha;
        this.startColor = startColor;
        this.endColor = endColor;
        this.trailHotPhaseTicks = Math.max(trailHotPhaseTicks, 0);
        this.trailHotColor = trailHotColor;
        this.fullBright = fullBright;
        this.lifetimeGate = lifetimeGate;
        this.lifetime = Math.max(lifetime, 1);
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
        applyCurve(0.0f, 0);
    }

    public static Particle createBody(ClientLevel level, Vec3 position,
                                      float startScale, float targetScale,
                                      int startColor, int endColor,
                                      int lifetime, boolean fullBright) {
        return new RVP_WhitePhosphorusParticle(
                level, position, true, startScale, targetScale, 1.0f, 1.0f,
                startColor, endColor, 0, 0, lifetime, fullBright, null);
    }

    public static Particle createTrail(ClientLevel level, Vec3 position,
                                       float startScale, float endScale,
                                       float startAlpha, float endAlpha,
                                       int startColor, int endColor,
                                       int trailHotPhaseTicks, int trailHotColor,
                                       int lifetime, boolean fullBright,
                                       RVP_TrailLifetimeGate lifetimeGate) {
        return new RVP_WhitePhosphorusParticle(
                level, position, false, startScale, endScale, startAlpha, endAlpha,
                startColor, endColor, trailHotPhaseTicks, trailHotColor,
                lifetime, fullBright, lifetimeGate);
    }

    @Override
    public void tick() {
        visualAge++;
        // 调用本项目尾迹寿命门：对应弹体飞行期间冻结尾迹年龄，落地或结束后恢复正常 Tick。
        if (!body && lifetimeGate != null && lifetimeGate.shouldPauseLifetime()) {
            xo = x;
            yo = y;
            zo = z;
            applyCurve(0.0f, visualAge);
            return;
        }
        super.tick();
        if (!removed) {
            int curveDuration = body ? Math.max(lifetime - 1, 1) : Math.max(lifetime, 1);
            float progress = Mth.clamp((float) age / curveDuration, 0.0f, 1.0f);
            applyCurve(progress, visualAge);
        }
    }

    private void applyCurve(float progress, int currentVisualAge) {
        float curve = progress * progress * (3.0f - 2.0f * progress);
        quadSize = Mth.lerp(curve, startScale, endScale);
        alpha = Mth.lerp(curve, startAlpha, endAlpha);
        float baseRed = Mth.lerp(progress, red(startColor), red(endColor));
        float baseGreen = Mth.lerp(progress, green(startColor), green(endColor));
        float baseBlue = Mth.lerp(progress, blue(startColor), blue(endColor));
        float hotBlendWeight = body
                ? 0.0f
                : resolveTrailHotBlendWeight(currentVisualAge, trailHotPhaseTicks);
        rCol = Mth.lerp(hotBlendWeight, baseRed, red(trailHotColor));
        gCol = Mth.lerp(hotBlendWeight, baseGreen, green(trailHotColor));
        bCol = Mth.lerp(hotBlendWeight, baseBlue, blue(trailHotColor));
    }

    /**
     * 计算尾迹高温色相对既有冷却颜色的混合权重；使用独立视觉年龄，不受寿命门冻结影响。
     *
     * @param visualAge 尾迹已经历的真实客户端 Tick 数
     * @param hotPhaseTicks 高温火光阶段时长，单位 Tick；0 表示禁用
     * @return 0～1 的高温色权重，出生时为 1，阶段结束时为 0
     */
    static float resolveTrailHotBlendWeight(int visualAge, int hotPhaseTicks) {
        if (hotPhaseTicks <= 0) {
            return 0.0f;
        }
        float progress = Mth.clamp(visualAge / (float) hotPhaseTicks, 0.0f, 1.0f);
        float smoothProgress = progress * progress * (3.0f - 2.0f * progress);
        return 1.0f - smoothProgress;
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
                    0xFFC247, 0xFFC247, 0, 0, 3, true, null);
        }
    }
}
