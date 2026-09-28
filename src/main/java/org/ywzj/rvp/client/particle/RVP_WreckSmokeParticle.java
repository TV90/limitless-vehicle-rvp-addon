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

/** 近处烟使用 16 帧残骸贴图，长程烟使用 boom/smoke.png 的 8 帧贴图。 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckSmokeParticle extends SingleQuadParticle {

    /** 近处烟横条的帧数：1024×64，单帧 64×64。 */
    private static final int NEAR_FRAME_COUNT = 16;
    /** 长程烟横条的帧数：boom/smoke.png 为 64×8，单帧 8×8。 */
    private static final int LONG_FRAME_COUNT = 8;
    /** 近处烟固定的东向微风速度，单位格/tick；长程烟使用会话参数。 */
    private static final double NEAR_EAST_BREEZE_SPEED = 0.057;
    /** 近处烟固定的微风摆动增量，单位格/tick²；长程烟使用会话参数。 */
    private static final double NEAR_BREEZE_SWAY_ACCELERATION = 0.0005;
    /** 近处烟的三种独立渲染类型，保留现有贴图和随机轮廓。 */
    private static final ParticleRenderType[] NEAR_RENDER_TYPES = {
            renderType("textures/wreck_smoke/wreck_smoke_dense_round.png"),
            renderType("textures/wreck_smoke/wreck_smoke_broad_roll.png"),
            renderType("textures/wreck_smoke/wreck_smoke_ragged_cluster.png")
    };
    /** 长程核心与外层共用已有的 boom/smoke.png，并保持不写深度的半透明渲染。 */
    private static final ParticleRenderType LONG_RENDER_TYPE = renderType("textures/boom/smoke.png");

    /** 烟团层次：近处烟沿用原参数，长程核心与外层分别提供浓度和轮廓体积。 */
    public enum SmokeLayer {
        /** 保留现有近处浓烟的尺寸、颜色和短寿命。 */
        NEAR,
        /** 长程烟柱的深色主体，持续上升超过出生点上方 100 格。 */
        LONG_CORE,
        /** 长程烟柱较大而透明的外轮廓，与主体错位形成层次。 */
        LONG_OUTER
    }

    /** 本粒子的贴图渲染类型，由烟团轮廓索引选择。 */
    private final ParticleRenderType renderType;
    /** 初始透明度；生命后段逐渐淡出，避免末帧突变。 */
    private final float initialAlpha;
    /** 初始面片半宽，单位格；生命期内随烟团扩散增长。 */
    private final float initialSize;
    /** 贴图当前帧的零基索引。 */
    private int frame;
    /** 每 tick 绕视线轴转动的弧度，防止多枚烟团轮廓完全重叠。 */
    private final float rollSpeed;
    /** 烟团所属层次，决定长程运动和核心、外层的颜色与透明度。 */
    private final SmokeLayer layer;
    /** 每枚烟团独立的微风相位，避免所有烟团同步摇摆。 */
    private final double breezePhase;
    /** 烟团出生时的世界 Y 坐标，用于按实际爬升高度增强长程东向风。 */
    private final double spawnY;

    private RVP_WreckSmokeParticle(ClientLevel level, double x, double y, double z,
                                  double vx, double vy, double vz, float size,
                                  int lifetime, int variant, SmokeLayer layer) {
        super(level, x, y, z);
        this.renderType = layer == SmokeLayer.NEAR
                ? NEAR_RENDER_TYPES[Math.floorMod(variant, NEAR_RENDER_TYPES.length)]
                : LONG_RENDER_TYPE;
        this.lifetime = Math.max(16, lifetime);
        this.layer = layer;
        this.spawnY = y;
        // 长程烟首帧从较稀疏的第 3 帧开始，避免生成瞬间闪现完整烟团。
        this.frame = layer == SmokeLayer.NEAR ? 0 : 2;
        this.initialSize = Math.max(0.15f, size);
        this.quadSize = initialSize;
        // 读取本项目调试参数：长程烟首帧使用当前东向风，近处烟保留固定微风。
        this.xd = (layer == SmokeLayer.NEAR ? NEAR_EAST_BREEZE_SPEED : Parameter.EAST_WIND.value()) + vx;
        this.yd = vy;
        this.zd = vz;
        this.breezePhase = level.random.nextDouble() * Mth.TWO_PI;
        this.hasPhysics = false;
        // 纯白 RGB 贴图按烟层染色：核心深而浓，外层浅而透，叠加后形成明暗层次。
        float shade = layer == SmokeLayer.LONG_OUTER
                ? 0.18f + level.random.nextFloat() * 0.10f
                : 0.08f + level.random.nextFloat() * 0.09f;
        this.rCol = shade;
        this.gCol = shade + 0.005f;
        this.bCol = shade + 0.015f;
        float baseAlpha = layer == SmokeLayer.LONG_OUTER
                ? 0.28f + level.random.nextFloat() * 0.12f
                : 0.66f + level.random.nextFloat() * 0.18f;
        // 读取本项目调试参数：核心和外层仅在新粒子出生时确定浓度，近处烟不变。
        this.initialAlpha = layer == SmokeLayer.NEAR ? baseAlpha
                : Mth.clamp(baseAlpha * (layer == SmokeLayer.LONG_CORE
                ? Parameter.CORE_ALPHA_SCALE.floatValue() : Parameter.OUTER_ALPHA_SCALE.floatValue()), 0.0f, 1.0f);
        this.alpha = initialAlpha;
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.rollSpeed = (level.random.nextFloat() - 0.5f) * 0.025f;
    }

    /** 构造一枚指定轮廓的残骸烟团；调用方决定位置、寿命和烟层。 */
    public static RVP_WreckSmokeParticle create(ClientLevel level, double x, double y, double z,
                                                 double vx, double vy, double vz,
                                                 float size, int lifetime, int variant, SmokeLayer layer) {
        return new RVP_WreckSmokeParticle(level, x, y, z, vx, vy, vz, size, lifetime, variant, layer);
    }

    /** 按完整贴图路径创建深度只测不写的半透明渲染类型，避免烟层遮断后绘制的实体和粒子。 */
    private static ParticleRenderType renderType(String texturePath) {
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
                return "RVP_WRECK_SMOKE_" + texturePath;
            }
        };
    }

    @Override
    public ParticleRenderType getRenderType() {
        return renderType;
    }

    @Override
    public int getLightColor(float partialTick) {
        // 天空光全亮，保证黑烟在夜间也能保留可见轮廓。
        return 15 << 20;
    }

    @Override
    protected float getU0() {
        return (float) frame / (layer == SmokeLayer.NEAR ? NEAR_FRAME_COUNT : LONG_FRAME_COUNT);
    }

    @Override
    protected float getU1() {
        return (float) (frame + 1) / (layer == SmokeLayer.NEAR ? NEAR_FRAME_COUNT : LONG_FRAME_COUNT);
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
        if (++this.age >= this.lifetime) {
            this.remove();
            return;
        }
        float progress = (float) this.age / this.lifetime;
        double rise = layer == SmokeLayer.NEAR ? 0.0 : Math.max(0.0, this.y - spawnY);
        if (layer != SmokeLayer.NEAR) {
            // 长程烟先反向成团，之后直到消失都使用 boom/smoke.png 最完整的第 1 帧。
            // 读取本项目调试参数：正在存活的长程烟也响应成团时间的修改。
            int formationTicks = Parameter.FORMATION_TICKS.intValue();
            if (this.age < formationTicks) {
                this.frame = 2 - Math.min(2, this.age * 3 / formationTicks);
            } else {
                this.frame = 0;
            }
            // 长程贴图按出生点以上的实际高度扩张，而非按存活时间估计上升距离。
            this.quadSize = initialSize * (1.0f + (float) rise * Parameter.SIZE_GAIN.floatValue());
            // 读取本项目调试参数：保持成熟期贴图轮廓，仅从指定寿命比例开始淡出。
            float fadeStart = Parameter.FADE_START.floatValue();
            this.alpha = initialAlpha * (progress < fadeStart ? 1.0f
                    : (1.0f - progress) / (1.0f - fadeStart));
        } else {
            this.frame = Mth.clamp((int) (progress * NEAR_FRAME_COUNT), 0, NEAR_FRAME_COUNT - 1);
            this.quadSize = initialSize * (0.75f + progress * 1.25f);
            this.alpha = initialAlpha * (progress < 0.6f ? 1.0f : (1.0f - progress) / 0.4f);
        }
        this.roll += rollSpeed;
        // 长程烟越高越受东向风推动：上层烟更靠东，形成自然弯曲的拖尾。
        double breezeWave = this.age * 0.06 + breezePhase;
        // 读取本项目调试参数：长程烟运动实时响应上升气流、东风、高度增益及摆动。
        double eastWind = layer == SmokeLayer.NEAR ? NEAR_EAST_BREEZE_SPEED : Parameter.EAST_WIND.value();
        double windGain = layer == SmokeLayer.NEAR ? 0.0 : Parameter.WIND_GAIN.value();
        double sway = layer == SmokeLayer.NEAR ? NEAR_BREEZE_SWAY_ACCELERATION : Parameter.SWAY.value();
        double targetEastSpeed = eastWind * (1.0 + rise * windGain);
        this.xd = targetEastSpeed + (this.xd - targetEastSpeed) * 0.96
                + Math.sin(breezeWave) * sway;
        this.zd = this.zd * 0.96 + Math.cos(breezeWave) * sway;
        // 长程烟逐渐进入稳定上升气流；近处烟保留原本的缓慢上浮速度。
        this.yd = layer != SmokeLayer.NEAR
                ? Parameter.UPDRAFT.value() + (this.yd - Parameter.UPDRAFT.value()) * 0.98
                : this.yd * 0.98 + 0.0015;
        this.move(this.xd, this.yd, this.zd);
    }
}
