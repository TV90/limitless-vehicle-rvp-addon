package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;

/**
 * MCHR 风格默认爆炸的发光粒子（复用 RVP 现成的 {@code textures/nuclear/flare.png} 径向渐变光斑，
 * 全亮自发光，独立渲染类型），承载三种模式：
 * <ul>
 *   <li>{@code EMBER}：MCHR 曳光火星——运动数学照 {@code MCH_EntityFlare}（pos += motion → 拖烟 →
 *       motionY -= 0.025 上飘 → 水平阻力 ×0.992），拖烟每 tick 2 团
 *       {@link RVP_MchrSmokeParticle#ofTrailDefault}（alpha ×0.75、50% 跳过）；命中方块/水即亡；</li>
 *   <li>{@code FLASH}：起爆闪光——爆心单个大光斑，快速放大并淡出（替代 vanilla
 *       EXPLOSION_EMITTER/EXPLOSION，原版形状不好看）；</li>
 *   <li>{@code SPARK}：飞溅火星——向外的快速小光斑，受重力下坠、随寿命收缩淡出。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class RVP_MchrFlareParticle extends SingleQuadParticle {

    /** 粒子模式。 */
    private enum Kind { EMBER, FLASH, SPARK }

    /** 最长寿命（EMBER：MCHR 服务端 ticksExisted &gt; 300 移除）。 */
    private static final int MAX_LIFETIME = 300;

    /** 火星淡黄 tint（MCHR 拖烟亮黄配色同源）。 */
    private static final float TINT_R = 1.0f;
    private static final float TINT_G = 0.9f;
    private static final float TINT_B = 0.6f;

    /** 独立发光渲染类型：绑定 flare.png 径向渐变光斑（中心白 → 边缘透明）。 */
    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            // 加色混合（SRC_ALPHA, ONE）：flare.png 的 alpha 全不透明、径向渐变画在 RGB
            // （中心白 → 边缘黑）——additive 下边缘黑色加 0 即不可见，天然无黑框
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, RVP_MOD.modLocation("textures/nuclear/flare.png"));
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator tesselator) {
            tesselator.end();
        }

        @Override
        public String toString() {
            return "RVP_MCHR_FLARE";
        }
    };

    private final Kind kind;
    /** EMBER：上飘重力；SPARK：下坠重力。 */
    private final float gravityPerTick;
    private final float startQuadSize;
    private final float startAlpha;

    private RVP_MchrFlareParticle(ClientLevel level, Kind kind, double x, double y, double z,
                                  double vx, double vy, double vz,
                                  float quadSize, int lifetime, float gravityPerTick) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.kind = kind;
        this.lifetime = Math.max(1, lifetime);
        this.quadSize = quadSize;
        this.startQuadSize = quadSize;
        this.gravityPerTick = gravityPerTick;
        this.startAlpha = 1.0f;
        this.hasPhysics = kind == Kind.EMBER; // EMBER 允许落地判定即亡；FLASH/SPARK 穿透
        // 初速（修复：此前构造器未赋值，导致火星/飞溅粒子零速度堆在爆心）
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = TINT_R;
        this.gCol = TINT_G;
        this.bCol = TINT_B;
        this.alpha = 1.0f;
        // 热成像自登记：曳光/火花/闪光在本体热成像视角下作为热源重画发白
        RVP_ThermalParticleChannel.register(this);
    }

    /** EMBER：曳光火星（拖烟 + 缓慢下坠长滞空；重力 0.012/tick，寿命 160~240 tick）。 */
    public static void spawnEmber(ParticleEngine engine, ClientLevel level,
                                  double x, double y, double z,
                                  double vx, double vy, double vz) {
        if (engine != null) {
            engine.add(new RVP_MchrFlareParticle(level, Kind.EMBER, x, y, z, vx, vy, vz,
                    0.5f, 160 + level.random.nextInt(80), 0.02f));
        }
    }

    /** FLASH：起爆闪光（scale = 半宽格数，寿命 8 tick，快速放大 + 淡出）。 */
    public static void spawnFlash(ParticleEngine engine, ClientLevel level,
                                  double x, double y, double z, float scale) {
        if (engine != null) {
            engine.add(new RVP_MchrFlareParticle(level, Kind.FLASH, x, y, z, 0.0, 0.0, 0.0,
                    scale * 0.5f, 8, 0.0f));
        }
    }

    /** SPARK：飞溅火星（水平为主向外抛射，重力 0.04/tick 下坠拉出弧线，收缩淡出）。 */
    public static void spawnSpark(ParticleEngine engine, ClientLevel level,
                                  double x, double y, double z,
                                  double vx, double vy, double vz, float scale, int lifetime) {
        if (engine != null) {
            engine.add(new RVP_MchrFlareParticle(level, Kind.SPARK, x, y, z, vx, vy, vz,
                    scale, lifetime, 0.08f));
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    @Override
    public int getLightColor(float partialTick) {
        // 全亮自发光（光斑贴图本身带径向衰减）
        return 0xF000F0;
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

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.kind == Kind.FLASH) {
            // 闪光：快速放大 + 淡出
            ++this.age;
            float progress = this.age / (float) this.lifetime;
            this.quadSize = this.startQuadSize * (1.0f + progress * 1.5f);
            this.alpha = this.startAlpha * (1.0f - progress) * (1.0f - progress);
            if (this.age >= this.lifetime) {
                this.remove();
            }
            return;
        }

        if (this.kind == Kind.SPARK) {
            // 飞溅火星：重力下坠 + 收缩淡出
            this.move(this.xd, this.yd, this.zd);
            this.yd -= this.gravityPerTick;
            this.xd *= 0.93f;
            this.zd *= 0.93f;
            ++this.age;
            float progress = this.age / (float) this.lifetime;
            this.quadSize = this.startQuadSize * (1.0f - progress * 0.6f);
            this.alpha = this.startAlpha * (1.0f - progress);
            if (this.age >= this.lifetime || this.onGround) {
                this.remove();
            }
            return;
        }

        // EMBER：MCH_EntityFlare 数学（pos += motion → 拖烟 → 上飘 → 水平阻力）
        this.move(this.xd, this.yd, this.zd);
        for (int i = 0; i < 2; i++) {
            if (this.level.random.nextInt(2) == 0) {
                continue;
            }
            Vec3 mid = new Vec3(
                    (this.xo + this.x) / 2.0,
                    (this.yo + this.y) / 2.0,
                    (this.zo + this.z) / 2.0);
            RVP_MchrSmokeParticle puff = RVP_MchrSmokeParticle.ofTrailSmall(this.level, mid.x, mid.y, mid.z);
            puff.scaleAlpha(0.75f);
            Minecraft.getInstance().particleEngine.add(puff);
        }
        this.yd -= this.gravityPerTick;
        this.xd *= 0.95f;
        this.zd *= 0.95f;
        ++this.age;
        if (this.age >= this.lifetime || this.onGround
                || this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).is(FluidTags.WATER)) {
            this.remove();
        }
    }
}
