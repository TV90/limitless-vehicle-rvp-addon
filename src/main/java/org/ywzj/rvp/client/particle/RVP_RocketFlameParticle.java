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
 *       驱动 R/G 衰减），随后熄灭为深灰随机烟；尺寸 {@code (rand*0.5 + 0.1 + 2*ageRatio) × scale}
 *       持续膨胀（约 0.35 → 2.6 × scale），透明度 {@code sqrt(1 - age/maxAge) × 0.75} 缓出淡出；
 *       渲染 3 层高斯抖动 quad（HBM 10 层的性能折衷）+ 横向抖动随寿命急剧扩大，营造
 *       "近弹尾细而亮、远弹尾粗而散"的锥形烟柱；</li>
 *   <li><b>WASH</b>（发射地面烟浪）：随机灰 0.25~0.75、寿命 80~100t、尺寸 0.25 → 2.25 × scale
 *       线性膨胀、膨胀量转浮升（HBM SmokePlume 技巧）、水平径向冲刷初速 + 0.925 阻尼。</li>
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

    /** 独立半透明渲染类型：绑定 particle_base.png、标准 alpha 混合（与 MchrSmoke/白磷同款契约）。 */
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
            return "RVP_ROCKET_FLAME";
        }
    };

    /** TRAIL 模式抖动 quad 层数（HBM 10 层的性能折衷；1 层为 WASH 模式标准渲染）。 */
    private static final int TRAIL_LAYERS = 3;
    /** TRAIL 模式火焰相位占比：寿命前 25% 亮橙焰，之后深灰烟（HBM dark 阈值）。 */
    private static final float FLAME_PHASE_RATIO = 0.25f;

    /** 粒子模式。 */
    private final Mode mode;
    /** 尺寸倍率（effects_data.missile_native_trail_particle_scale 透传）。 */
    private final float sizeScale;
    /** WASH 模式寿命末端目标半宽（0.25 → 该值 × sizeScale 线性膨胀）；TRAIL 模式不使用。 */
    private float washEndQuad;

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
            // HBM ParticleRocketFlame：寿命 60~80t，出生即按 ageRatio=0 出曲线
            this.lifetime = 60 + this.random.nextInt(20);
        } else {
            // HBM ParticleSmokePlume：寿命 80~100t，0.25 起步线性膨胀（无碰撞，贴地扩散后浮升）
            this.lifetime = 80 + this.random.nextInt(20);
            this.washEndQuad = 2.25f * this.sizeScale;
            this.quadSize = 0.25f * this.sizeScale;
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
                Math.cos(angle) * horizontal, 0.05D, Math.sin(angle) * horizontal, sizeScale);
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
            // HBM ParticleSmokePlume：阻尼 0.925；尺寸线性膨胀，膨胀量转为浮升速度
            this.xd *= 0.925D;
            this.yd *= 0.925D;
            this.zd *= 0.925D;
            float prevQuad = this.quadSize;
            this.quadSize = Mth.lerp((float) this.age / this.lifetime, 0.25f * this.sizeScale, this.washEndQuad);
            this.move(this.xd, this.yd + (this.quadSize - prevQuad), this.zd);
        }
        this.applyTrailCurve((float) this.age / this.lifetime);
    }

    /**
     * 按寿命进度刷新颜色/透明度/尺寸（TRAIL 模式）：
     * 火焰相位 {@code dark = 1 - age/(maxAge*FLAME_PHASE_RATIO)} 驱动 R/G 衰减，
     * 熄火后落入 {@code rand*0.3} 的深灰随机烟；α 平方根缓出；quad 半宽持续膨胀。
     */
    private void applyTrailCurve(float ageRatio) {
        if (this.mode != Mode.TRAIL) {
            // WASH：随机灰出生时定色、线性淡出
            if (this.age == 0) {
                float grey = 0.25f + this.random.nextFloat() * 0.5f;
                this.rCol = grey;
                this.gCol = grey;
                this.bCol = grey;
            }
            this.alpha = (1.0f - ageRatio) * 0.9f;
            return;
        }
        float dark = 1.0f - Math.min(ageRatio / FLAME_PHASE_RATIO, 1.0f);
        // HBM 原式：R = dark + rand*0.3、G = 0.6*dark + rand*0.3、B = rand*0.3（顶点色上限钳 1）
        this.rCol = Math.min(dark + this.random.nextFloat() * 0.3f, 1.0f);
        this.gCol = Math.min(0.6f * dark + this.random.nextFloat() * 0.3f, 1.0f);
        this.bCol = this.random.nextFloat() * 0.3f;
        this.alpha = Mth.sqrt(Math.max(1.0f - ageRatio, 0.0f)) * 0.75f;
        // HBM 原式：quad 半宽 = (rand*0.5 + 0.1 + 2*ageRatio) × scale
        this.quadSize = (this.random.nextFloat() * 0.5f + 0.1f + 2.0f * ageRatio) * this.sizeScale;
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
        float spread = this.mode == Mode.TRAIL
                ? (float) Math.pow(1.0D + 4.0D * ageRatio, 1.5D) : 1.0f;
        int light = this.getLightColor(partialTicks);
        float u0 = this.getU0();
        float u1 = this.getU1();
        float v0 = this.getV0();
        float v1 = this.getV1();
        for (int layer = 0; layer < layers; layer++) {
            float quadSize = this.getQuadSize(partialTicks);
            if (this.mode == Mode.TRAIL) {
                quadSize = (this.random.nextFloat() * 0.5f + 0.1f + 2.0f * ageRatio) * this.sizeScale;
            }
            // 目的：层间位置抖动营造体积感；XZ 小抖 + Y 大抖，幅度随寿命扩大（HBM spread 同源）
            float jitterX = (float) this.random.nextGaussian() * 0.2f * spread;
            float jitterY = (float) this.random.nextGaussian() * 0.5f * spread;
            float jitterZ = (float) this.random.nextGaussian() * 0.2f * spread;
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
                corner.rotate(rotation);
                corner.mul(quadSize);
                corner.add(cx, cy, cz);
            }
            buffer.vertex(corners[0].x(), corners[0].y(), corners[0].z()).uv(u1, v1)
                    .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
            buffer.vertex(corners[1].x(), corners[1].y(), corners[1].z()).uv(u1, v0)
                    .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
            buffer.vertex(corners[2].x(), corners[2].y(), corners[2].z()).uv(u0, v0)
                    .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
            buffer.vertex(corners[3].x(), corners[3].y(), corners[3].z()).uv(u0, v1)
                    .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
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
