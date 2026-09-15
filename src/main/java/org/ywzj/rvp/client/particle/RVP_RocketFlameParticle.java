package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * [RVP] HBM 风格火箭尾焰粒子（弹道导弹"先火后烟"柱状尾迹 + 发射段贴地烟浪），
 * 按 HBM {@code ParticleRocketFlame} / {@code ParticleSmokePlume} 的更新与渲染数学移植
 * （HBM 为 1.7.10 EntityFX，本类按 1.20.1 SingleQuadParticle 习惯重写，机制与数值保持同源）：
 * <ul>
 *   <li><b>TRAIL</b>（飞行尾迹）：寿命前 25% 是亮橙黄火焰团（{@code dark = 1 - age/(maxAge*0.25)}
 *       驱动橙焰→灰烟过渡），随后熄灭为 R=G=B 的中性深灰烟（黑烟观感）；
 *       尺寸 {@code (0.5 + rand*0.3 + 1.3×ageRatio) × scale} 持续膨胀（出生 0.5~0.8、
 *       末端约 1.8~2.1 × scale），透明度 {@code sqrt(1 - age/maxAge) × 0.75} 缓出淡出；寿命 45~65t；
 *       渲染 3 层高斯抖动 quad（HBM 10 层的性能折衷），层间抖动随寿命线性温和扩大
 *       （最大 2.5×——大发散只归属地面烟浪，空中尾迹保持柱状观感）；</li>
 *   <li><b>WASH</b>（发射地面烟浪）：随机灰 0.25~0.75、寿命 80~100t、尺寸 0.3 → 3.0 × scale
 *       线性膨胀、膨胀量转浮升（HBM SmokePlume 技巧）、水平径向冲刷初速 + 0.925 阻尼。
 *       "大发散"归属地：烟浪靠径向初速 + 大尺寸膨胀在地面铺开，与空中柱状尾迹观感分离。</li>
 * </ul>
 * 贴图复用已迁移的 HBM {@code textures/nuclear/particle_base.png}（软圆斑底样，16×16，
 * RGB tint 乘算出颜色），独立 RenderType + 全亮光照，不依赖粒子图集/SpriteSet。
 * 数值来源见 {@code docs/plan/RVP弹道导弹尾迹HBM风格移植方案_20260915.md} 参数对照表。
 */
@OnlyIn(Dist.CLIENT)
public class RVP_RocketFlameParticle extends SingleQuadParticle {

    /** 粒子模式：飞行尾迹（先火后烟膨胀柱）/ 发射地面烟浪（贴地冲刷灰烟）。 */
    public enum Mode { TRAIL, WASH }

    /** HBM particle_base.png（此前核爆特效迁移时复制的临时资产，软圆斑底样）。 */
    private static final ResourceLocation PARTICLE_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/particle_base.png");

    /**
     * 独立半透明渲染类型：绑定 particle_base.png、标准 alpha 混合。
     *
     * <p><b>深度只测不写</b>（{@code depthMask(false)}）——对齐核爆云/冲击波/MchrFlare 的
     * 半透明约定（{@code RVP_ExplosionVisualManager}/{@code RVP_NuclearShockwaveRenderer} 同款）：
     * 半透明烟若写深度，会把后续绘制的粒子/弹体切片挡死，多层叠加后载具实体完全不可见
     * （2026-09-15 实机反馈修正）。深度<b>测试</b>保持开启，地形仍正常遮挡烟。</p>
     */
    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            RenderSystem.depthMask(false);
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
            return "RVP_ROCKET_FLAME";
        }
    };

    /** TRAIL 模式抖动 quad 层数（HBM 10 层的性能折衷；1 层为 WASH 模式标准渲染）。 */
    private static final int TRAIL_LAYERS = 3;
    /** TRAIL 模式火焰相位占比：寿命前 25% 亮橙焰，之后深灰烟（HBM dark 阈值）。 */
    private static final float FLAME_PHASE_RATIO = 0.25f;
    /** 烟相位灰度下限（R=G=B 的中性灰，黑烟观感；灰度上限 = 下限 + SPREAD）。 */
    private static final float SMOKE_GREY_MIN = 0.15f;
    /** 烟相位灰度随机幅度（保持单通道同值，避免逐通道独立随机产生彩色噪点）。 */
    private static final float SMOKE_GREY_SPREAD = 0.15f;
    /** WASH 模式寿命末端的高宽比（白云扩散期压扁为横铺贴地形态；初始脏灰烟为 1:1 圆团）。 */
    private static final float WASH_FLATTEN_END = 0.45f;

    /** 粒子模式。 */
    private final Mode mode;
    /** 尺寸倍率（effects_data.missile_native_trail_particle_scale 透传）。 */
    private final float sizeScale;
    /** WASH 模式寿命末端目标半宽（0.3 → 该值 × sizeScale 线性膨胀）；TRAIL 模式不使用。 */
    private float washEndQuad;
    /** WASH 模式出生时的脏灰基调（中末期向近白过渡的起点）；TRAIL 模式不使用。 */
    private float washBirthGrey = 0.5f;

    private RVP_RocketFlameParticle(ClientLevel level, Mode mode,
                                    double x, double y, double z,
                                    double vx, double vy, double vz,
                                    float sizeScale) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.mode = mode;
        this.sizeScale = Math.max(sizeScale, 0.0f);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.hasPhysics = false;
        this.gravity = 0.0f;
        if (mode == Mode.TRAIL) {
            // HBM ParticleRocketFlame：寿命 45~65t（较原版 60~80t 缩短，压低同屏存活粒子数）
            this.lifetime = 45 + this.random.nextInt(20);
        } else {
            // HBM ParticleSmokePlume：寿命 80~100t 基准，随尺寸倍率（尾迹 scale × 发射段加粗）
            // 等比提升滞留时长——烟越大滞留越久（2026-09-15 实机需求），钳制 [40, 600] 防极端配置
            this.lifetime = Mth.clamp(
                    Math.round((80 + this.random.nextInt(20)) * Math.max(sizeScale, 0.25f)), 40, 600);
            this.washEndQuad = 3.0f * this.sizeScale;
            this.quadSize = 0.3f * this.sizeScale;
        }
        this.applyTrailCurve(0.0f);
    }

    /**
     * 飞行尾迹入口（HBM ParticleRocketFlame 移植）。
     *
     * @param mx/my/mz 初速：弹轴反方向（-lookAngle）× 1.0，由调用方传入
     * @param sizeScale 尺寸倍率（对标本体大弹 1.0 / 小弹 0.5 的 getContrailScale 语义）
     */
    public static RVP_RocketFlameParticle ofTrail(ClientLevel level, double x, double y, double z,
                                                  double mx, double my, double mz, float sizeScale) {
        return new RVP_RocketFlameParticle(level, Mode.TRAIL, x, y, z, mx, my, mz, sizeScale);
    }

    /**
     * 发射地面烟浪入口（HBM ParticleSmokePlume 移植）：初速取随机水平径向冲刷
     * （0.5~0.9，发射点向外）+ 微升，与 HBM 发射台 {@code launchSmoke} 同源。
     */
    public static RVP_RocketFlameParticle ofLaunchWash(ClientLevel level, double x, double y, double z,
                                                       float sizeScale) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double horizontal = 0.5D + level.random.nextDouble() * 0.4D;
        return new RVP_RocketFlameParticle(level, Mode.WASH, x, y, z,
                Math.cos(angle) * horizontal, 0.02D, Math.sin(angle) * horizontal, sizeScale);
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
        if (this.mode == Mode.TRAIL) {
            // HBM ParticleRocketFlame：三轴阻尼 0.91，无重力
            this.xd *= 0.91D;
            this.yd *= 0.91D;
            this.zd *= 0.91D;
            this.move(this.xd, this.yd, this.zd);
        } else {
            // HBM ParticleSmokePlume：阻尼 0.925；尺寸线性膨胀，膨胀量转浮升——
            // 但浮升随寿命衰减（riseScale 1→0.2）：趋白压扁期云贴地摊开而不是升空飘走
            //（原恒定浮升一生累计上升 ~4-5 格，2026-09-15 实机反馈"白云直接飘上去"）
            this.xd *= 0.925D;
            this.yd *= 0.925D;
            this.zd *= 0.925D;
            float prevQuad = this.quadSize;
            this.quadSize = Mth.lerp((float) this.age / this.lifetime, 0.3f * this.sizeScale, this.washEndQuad);
            float riseScale = 1.0f - 0.8f * ((float) this.age / this.lifetime);
            this.move(this.xd, (this.yd + (this.quadSize - prevQuad)) * riseScale, this.zd);
        }
        this.applyTrailCurve((float) this.age / this.lifetime);
    }

    /**
     * 按寿命进度刷新颜色/透明度/尺寸（TRAIL 模式）：
     * 火焰相位 {@code dark = 1 - age/(maxAge*FLAME_PHASE_RATIO)} 驱动橙焰→灰烟过渡；
     * 烟相位为 R=G=B 的<b>单一中性灰</b>（黑烟观感）——不做逐通道独立随机
     * （HBM 原式的 R/G/B 各自掷随机在单贴图 alpha 混合下会呈现彩色噪点，2026-09-15 实机反馈修正）；
     * α 平方根缓出；quad 半宽持续膨胀。
     */
    private void applyTrailCurve(float ageRatio) {
        if (this.mode != Mode.TRAIL) {
            // WASH：出生定脏灰基调；中末期向近白过渡（凝结云观感）+ 线性淡出
            if (this.age == 0) {
                washBirthGrey = 0.25f + this.random.nextFloat() * 0.5f;
            }
            // 目的：中末期趋白——whiten 权重在寿命 30%~70% 间 smoothstep 爬升、封顶 0.9 混合
            // （保留一点灰底纹理，不至纯白平板）
            float whiten = Mth.clamp((ageRatio - 0.3f) / 0.4f, 0.0f, 1.0f);
            whiten = whiten * whiten * (3.0f - 2.0f * whiten);
            float whiteBlend = whiten * 0.9f;
            float color = Mth.lerp(whiteBlend, washBirthGrey, 0.95f);
            this.rCol = color;
            this.gCol = color;
            this.bCol = color;
            this.alpha = (1.0f - ageRatio) * 0.9f;
            return;
        }
        float dark = 1.0f - Math.min(ageRatio / FLAME_PHASE_RATIO, 1.0f);
        // 烟相位基准：单一灰度值（R=G=B，黑烟）；火焰相位向亮橙过渡（随机幅度收窄到 ±0.1 保色相统一）
        float grey = SMOKE_GREY_MIN + this.random.nextFloat() * SMOKE_GREY_SPREAD;
        this.rCol = Mth.lerp(dark, grey, Math.min(1.0f + this.random.nextFloat() * 0.1f, 1.0f));
        this.gCol = Mth.lerp(dark, grey, 0.6f + this.random.nextFloat() * 0.1f);
        this.bCol = Mth.lerp(dark, grey, this.random.nextFloat() * 0.1f);
        this.alpha = Mth.sqrt(Math.max(1.0f - ageRatio, 0.0f)) * 0.75f;
        this.quadSize = trailQuadSize(ageRatio);
    }

    /** TRAIL 半宽曲线（半宽格）：{@code (0.5 + rand*0.3 + 1.3×ageRatio) × scale}——出生 0.5~0.8、
     * 末端约 1.8~2.1 × scale（2026-09-15 第三轮实机反馈：近地面烟柱由年轻小粒子主导显得太细，
     * 出生尺寸与增长系数同步加大，整条烟柱加粗）。 */
    private float trailQuadSize(float ageRatio) {
        return (0.5f + this.random.nextFloat() * 0.3f + 1.3f * ageRatio) * this.sizeScale;
    }

    /**
     * TRAIL 模式渲染 {@link #TRAIL_LAYERS} 层抖动 quad（HBM 逐帧多层叠加的伪体积感）：
     * 每层独立随机半宽与位置抖动，抖动幅度随寿命以 {@code (1 + 4*ageRatio)^1.5} 急剧扩大
     * （柱状 → 散开云）；WASH 模式退化为单 quad 标准渲染。
     */
    @Override
    public void render(VertexConsumer buffer, Camera renderInfo, float partialTicks) {
        int layers = this.mode == Mode.TRAIL ? TRAIL_LAYERS : 1;
        Vec3 cameraPos = renderInfo.getPosition();
        float baseX = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cameraPos.x());
        float baseY = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cameraPos.y());
        float baseZ = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cameraPos.z());
        Quaternionf rotation = renderInfo.rotation();
        float ageRatio = (float) this.age / this.lifetime;
        // 目的：空中尾迹保持"柱状"——末端发散用线性小系数（最大 2.5×，2026-09-15 从 HBM 的
        // (1+4·ageRatio)^1.5≈11× 收敛：原式末端 Y 抖动 ±5 格以上，观感为烟柱末端炸散成云）。
        // 大发散只归属地面烟浪 WASH（其靠径向初速+尺寸膨胀发散，不走本系数）。
        float spread = this.mode == Mode.TRAIL ? 1.0f + 1.5f * ageRatio : 1.0f;
        int light = this.getLightColor(partialTicks);
        // 目的：3 层抖动 quad 若每层都用全量 α（0.75），叠加等效不透明度 ≈ 1-(0.25)³ ≈ 98%，
        // 烟柱即实心墙——层间按 1/N 分摊 α，叠加后 ≈ 58%，半透明可透见载具（核爆云观感）
        float layerAlpha = this.alpha * (this.mode == Mode.TRAIL ? 1.0f / TRAIL_LAYERS : 1.0f);
        float u0 = this.getU0();
        float u1 = this.getU1();
        float v0 = this.getV0();
        float v1 = this.getV1();
        for (int layer = 0; layer < layers; layer++) {
            // 目的：每层独立掷半宽（层间尺寸差 + 位置抖动共同构成体积感）；曲线同 tick 期一致
            float quadSize = this.mode == Mode.TRAIL ? trailQuadSize(ageRatio) : this.getQuadSize(partialTicks);
            // 目的：层间位置抖动为 TRAIL 专属（体积感；XZ 小抖 + Y 大抖，幅度随寿命扩大）。
            // WASH 严禁逐帧抖动——单层大 quad 每帧重掷高斯会呈现高频颤动（2026-09-15 实机反馈），
            // 其位置只由径向初速 + 浮升驱动。
            float jitterX = 0.0F;
            float jitterY = 0.0F;
            float jitterZ = 0.0F;
            // 目的：WASH 随寿命压扁——初始脏灰烟 1:1 圆团，扩散发白期线性压到 0.45 高宽比
            // （横铺的贴地扁白云；在 billboard 本地纵轴上缩放，朝向仍取相机旋转）
            float yScale = 1.0F;
            if (this.mode == Mode.TRAIL) {
                jitterX = (float) this.random.nextGaussian() * 0.2f * spread;
                jitterY = (float) this.random.nextGaussian() * 0.5f * spread;
                jitterZ = (float) this.random.nextGaussian() * 0.2f * spread;
            } else {
                yScale = Mth.lerp(ageRatio, 1.0F, WASH_FLATTEN_END);
            }
            Vector3f[] corners = new Vector3f[]{
                    new Vector3f(-1.0F, -1.0F, 0.0F),
                    new Vector3f(-1.0F, 1.0F, 0.0F),
                    new Vector3f(1.0F, 1.0F, 0.0F),
                    new Vector3f(1.0F, -1.0F, 0.0F)};
            // 目的：billboard 朝向取相机旋转，抖动只影响层中心不破坏面向
            float cx = baseX + jitterX;
            float cy = baseY + jitterY;
            float cz = baseZ + jitterZ;
            for (Vector3f corner : corners) {
                corner.y *= yScale;
                corner.rotate(rotation);
                corner.mul(quadSize);
                corner.add(cx, cy, cz);
            }
            buffer.vertex(corners[0].x(), corners[0].y(), corners[0].z()).uv(u1, v1)
                    .color(this.rCol, this.gCol, this.bCol, layerAlpha).uv2(light).endVertex();
            buffer.vertex(corners[1].x(), corners[1].y(), corners[1].z()).uv(u1, v0)
                    .color(this.rCol, this.gCol, this.bCol, layerAlpha).uv2(light).endVertex();
            buffer.vertex(corners[2].x(), corners[2].y(), corners[2].z()).uv(u0, v0)
                    .color(this.rCol, this.gCol, this.bCol, layerAlpha).uv2(light).endVertex();
            buffer.vertex(corners[3].x(), corners[3].y(), corners[3].z()).uv(u0, v1)
                    .color(this.rCol, this.gCol, this.bCol, layerAlpha).uv2(light).endVertex();
        }
    }

    @Override
    public int getLightColor(float partialTick) {
        // HBM：亮度 240 全亮（不受环境光）——尾焰/烟浪夜里同样可见
        return LightTexture.FULL_BRIGHT;
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
}
