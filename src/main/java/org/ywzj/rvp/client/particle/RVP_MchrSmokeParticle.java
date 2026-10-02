package org.ywzj.rvp.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * MCHR 风格默认爆炸的"翻滚灰黄大烟"——按 {@code MCH_EntityParticleSmoke} 渲染/更新数学逐条移植，
 * 渲染走 {@link RVP_MchrSmokeRenderType}（直接绑定 MCHR 原版 smoke.png 灰白底样，独立于粒子图集，
 * 不依赖 SpriteSet/粒子描述 JSON）：
 * <ul>
 *   <li>渲染尺寸 = {@code 0.1 × particleScale}（quadSize 即半宽）；diffusible 下初始
 *       {@code scale = size × 0.2}、上限 {@code size × 2.0}、每 tick {@code +0.8} 线性扩散
 *       （巨型翻滚烟团观感的核心）；</li>
 *   <li>颜色/透明度生成时确定并全程恒定（爆炸烟不转白、无渐隐，生命末帧直接消失）；
 *       灰度底样 × tint（0.3~0.7 灰黄）= MCHR 实际观感；</li>
 *   <li>运动：三轴阻尼 0.96 + motionY += 0.001 慢升（motionYUpAge 缺省 2.0 永不触发上浮相位）；</li>
 *   <li>帧：8 帧横排按 {@code 8 × age/maxAge} 推进（UV 自算，帧 0 实心 → 帧 7 破碎的消散感）；</li>
 *   <li>亮度：天空光采样（block 0 / sky 15），不吃方块光。</li>
 * </ul>
 * 数值由 {@code RVP_DefaultExplosionEffectFactory} 按 {@code MCH_Explosion.effectExplosion:226-268} 内置推导。
 */
@OnlyIn(Dist.CLIENT)
public class RVP_MchrSmokeParticle extends SingleQuadParticle {

    /** MCHR particleScale（扩散变量，渲染半宽 = 0.1 × scale）。 */
    private float mchrScale;
    /** 扩散上限（= size × 2.0 的 MCHR scale 值）。 */
    private final float maxScale;
    /** 当前帧索引（8 帧横排，UV 自算）。 */
    private int frame;
    /** 渲染类型（默认 MCHR 烟贴图；114514 彩蛋事件切换 EGG 贴图，布局一致）。 */
    private ParticleRenderType renderType = RVP_MchrSmokeRenderType.RENDER_TYPE;

    /**
     * splash 物理开关（2026-10-02，水下爆炸水幕专用）：true = MCHR
     * {@code MCH_EntityParticleSplash} 物理（固定尺寸、无阻尼、重力 -0.06/tick 回落）；
     * false（默认）= MCHR diffusible 烟物理（扩散 + 阻尼 0.96 + 慢升 0.001）。
     */
    private boolean splashPhysics = false;

    private RVP_MchrSmokeParticle(ClientLevel level, double x, double y, double z,
                                  double vx, double vy, double vz,
                                  float size, float r, float g, float b, float alpha,
                                  int lifetime) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.lifetime = Math.max(1, lifetime);
        // MCH_ParticlesUtil.spawnParticle：diffusible → scale0 = size×0.2，maxScale = size×2.0
        this.mchrScale = size * 0.2f;
        this.maxScale = size * 2.0f;
        this.quadSize = 0.1f * this.mchrScale;   // 渲染半宽 = 0.1 × scale
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.gravity = 0.001f;                    // MCHR p.gravity = 0.001f
        this.rCol = r;
        this.gCol = g;
        this.bCol = b;
        this.alpha = alpha;
        this.hasPhysics = false;
        this.frame = 0;
        // 热成像自登记：MCHR 爆炸烟（视觉工厂烟体）在本体热成像视角下作为热源重画发白
        RVP_ThermalParticleChannel.register(this);
    }

    /** 工厂入口：按 {@code MCH_Explosion.effectExplosion:226-268} 参数构造灰黄大烟。 */
    public static RVP_MchrSmokeParticle of(ClientLevel level, double x, double y, double z,
                                           double vx, double vy, double vz,
                                           float size, int lifetime) {
        net.minecraft.util.RandomSource random = level.random;
        float base = 0.3f + random.nextFloat() * 0.4f;
        float r = base + 0.1f;
        float g = base + 0.05f;
        float b = base;
        float alpha = 0.4f + random.nextFloat() * 0.4f;
        return new RVP_MchrSmokeParticle(level, x, y, z, vx, vy, vz,
                size, r, g, b, alpha, lifetime);
    }

    /**
     * 损坏冒烟入口（2026-09-28）：深灰→近黑 tint——贴图为灰白底样，tint 越暗烟越黑
     * （灰度底样 × 暗色乘算）。与 {@link #of}（爆炸烟灰黄随机）分离，调色互不影响：
     * MCHR 普通爆炸烟保持原观感。
     */
    public static RVP_MchrSmokeParticle ofDark(ClientLevel level, double x, double y, double z,
                                               double vx, double vy, double vz,
                                               float size, int lifetime) {
        net.minecraft.util.RandomSource random = level.random;
        float base = 0.06f + random.nextFloat() * 0.12f;
        return new RVP_MchrSmokeParticle(level, x, y, z, vx, vy, vz,
                size, base + 0.02f, base + 0.01f, base, 0.55f + random.nextFloat() * 0.25f, lifetime);
    }

    /** 拖烟入口：MCHR 烟构造默认（亮灰 0.7~1.0、scale 5~5.5、寿命 18~84）。 */
    public static RVP_MchrSmokeParticle ofTrailDefault(ClientLevel level, double x, double y, double z) {
        net.minecraft.util.RandomSource random = level.random;
        float grey = random.nextFloat() * 0.3f + 0.7f;
        float size = random.nextFloat() * 0.5f + 5.0f;
        int lifetime = (int) (16.0 / (random.nextDouble() * 0.8 + 0.2)) + 2;
        return new RVP_MchrSmokeParticle(level, x, y, z, 0.0, 0.0, 0.0,
                size, grey, grey, grey, 1.0f, lifetime);
    }

    /** 拖烟入口（小型淡化版）：火星拖尾专用——小团（scale 2~2.5）、低不透明度 0.45、短寿命。 */
    public static RVP_MchrSmokeParticle ofTrailSmall(ClientLevel level, double x, double y, double z) {
        net.minecraft.util.RandomSource random = level.random;
        float grey = random.nextFloat() * 0.3f + 0.7f;
        float size = random.nextFloat() * 0.5f + 2.0f;
        int lifetime = (int) (12.0 / (random.nextDouble() * 0.8 + 0.2)) + 2;
        return new RVP_MchrSmokeParticle(level, x, y, z, 0.0, 0.0, 0.0,
                size, grey, grey, grey, 0.45f, lifetime);
    }

    /**
     * 弹体尾迹入口（可调尺寸倍率）：观感同 {@link #ofTrailDefault}（亮灰 0.7~1.0、寿命 18~84），
     * 烟团尺寸按倍率缩放。
     *
     * <p>供 {@code effects_data.missile_native_trail_particle_style} 取 {@code rvp_smoke} 时使用——本类把尺寸
     * 直接烘进构造（{@code mchrScale = size × 0.2}、上限 {@code size × 2.0}、{@code quadSize = 0.1 × scale}，
     * 并每 tick {@code +0.8} 扩散到上限），所以"调粗尾迹"只需改这一个倍率，
     * 不依赖原版粒子的 {@code scale()} 行为，也不增加粒子数量。</p>
     *
     * @param sizeScale 尺寸倍率（{@code <= 0} 按 0 处理）；{@code 1} = 与拖烟默认一致
     */
    public static RVP_MchrSmokeParticle ofTrailScaled(ClientLevel level, double x, double y, double z,
                                                      float sizeScale) {
        net.minecraft.util.RandomSource random = level.random;
        float grey = random.nextFloat() * 0.3f + 0.7f;
        float size = (random.nextFloat() * 0.5f + 5.0f) * Math.max(sizeScale, 0f);
        int lifetime = (int) (16.0 / (random.nextDouble() * 0.8 + 0.2)) + 2;
        return new RVP_MchrSmokeParticle(level, x, y, z, 0.0, 0.0, 0.0,
                size, grey, grey, grey, 1.0f, lifetime);
    }

    /** 按倍率衰减透明度（alpha 为父类 protected，须在类内修改；供火星拖烟 ×0.75 使用）。 */
    public RVP_MchrSmokeParticle scaleAlpha(float multiplier) {
        this.alpha *= multiplier;
        return this;
    }

    /**
     * 水下爆炸水幕粒子入口（2026-10-02，MCHR {@code effectExplosionInWater} 复刻）：
     * 参数按 MCHR {@code MCH_EntityParticleSplash} 构造逐字——白色 0.7~1.0、
     * particleScale 5~5.5（渲染半宽 0.5 格）、寿命 80/(rand×0.8+0.2)+2
     * （MCHR 上限 402 tick，此处封顶 200 = 10 秒防长寿命堆积），并启用
     * <b>splash 专属物理</b>（固定尺寸、无阻尼、重力 -0.06/tick——水幕升而复落，
     * 首版误用烟物理慢升不回落，用户实测后修正）。速度由调用方按 MCHR
     * 主循环公式传入（中心冲天、边缘悬停的穹顶水幕）。载体复用本烟粒子类（白色调色，
     * MCHR 专用白色 splash 贴图不迁移）。
     */
    public static RVP_MchrSmokeParticle ofSplash(ClientLevel level, double x, double y, double z,
                                                 double mx, double my, double mz) {
        net.minecraft.util.RandomSource random = level.random;
        float white = random.nextFloat() * 0.3f + 0.7f;
        float size = random.nextFloat() * 0.5f + 5.0f;
        int lifetime = Math.min(200, (int) (80.0D / (random.nextDouble() * 0.8D + 0.2D)) + 2);
        RVP_MchrSmokeParticle particle = new RVP_MchrSmokeParticle(level, x, y, z, mx, my, mz,
                size, white, white, white, 0.9f, lifetime);
        particle.splashPhysics = true;
        return particle;
    }

    /** 切换渲染类型（链式）：爆炸工厂在 114514 彩蛋事件时切到 EGG 贴图（布局与 smoke.png 一致）。 */
    public RVP_MchrSmokeParticle withRenderType(ParticleRenderType renderType) {
        this.renderType = renderType;
        return this;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return renderType;
    }

    @Override
    public int getLightColor(float partialTick) {
        // MCHR：亮度采样 y+3000 处 → block light 0 / sky light 15（天空光全亮、不吃方块光）
        return 15 << 20;
    }

    /** 8 帧横排 UV 自算（帧 0 实心圆斑 → 帧 7 破碎，MCHR smoke.png 帧序）。 */
    @Override
    protected float getU0() {
        return (float) this.frame / RVP_MchrSmokeRenderType.FRAME_COUNT;
    }

    @Override
    protected float getU1() {
        return (float) (this.frame + 1) / RVP_MchrSmokeRenderType.FRAME_COUNT;
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
        ++this.age;
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }
        if (this.splashPhysics) {
            // MCHR MCH_EntityParticleSplash 物理逐字：固定尺寸（无扩散）、无阻尼、
            // motionY -= 0.06/tick（升而复落的水幕回落），帧动画共用 8 帧序
            this.yd -= 0.06D;
        } else {
            // MCHR diffusible：scale += 0.8/tick（封顶 maxScale），渲染半宽 = 0.1 × scale
            if (this.mchrScale < this.maxScale) {
                this.mchrScale = Math.min(this.maxScale, this.mchrScale + 0.8f);
            }
            this.quadSize = 0.1f * this.mchrScale;
            // MCHR：三轴阻尼 0.96（diffusible），motionY += gravity（0.001 慢升，无上浮相位）
            this.xd *= 0.96f;
            this.yd *= 0.96f;
            this.zd *= 0.96f;
            this.yd += this.gravity;
        }
        // MCHR 帧：8 × age/maxAge
        this.frame = Mth.clamp((int) (8.0f * this.age / this.lifetime), 0,
                RVP_MchrSmokeRenderType.FRAME_COUNT - 1);
        this.move(this.xd, this.yd, this.zd);
    }

    /** Provider：常规粒子分发兜底（正常路径走工厂静态构造；registerSpecial 注册，无 SpriteSet 依赖）。 */
    public record Provider() implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return ofTrailDefault(level, x, y, z);
        }
    }
}
