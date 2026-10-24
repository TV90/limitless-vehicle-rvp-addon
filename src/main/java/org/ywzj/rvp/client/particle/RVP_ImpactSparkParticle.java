package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.all.RVP_ImpactSparkOptions;

/**
 * 弹药命中火星（{@code rvp:impact_spark}）：机枪/机炮弹药命中载具或方块后，在命中点溅出的扩散火花。
 *
 * <ul>
 *   <li><b>方向</b>：以调用方给的基准方向（= 命中方向的反方向）为轴，在锥内随机取一条母线，
 *       因此整体朝弹源侧/反弹侧溅出，同时有自然散布；</li>
 *   <li><b>速度</b>：基准速度由命中弹速打折而来（刻意不是原速），每颗再乘 0.6～1.0 的随机浮动；</li>
 *   <li><b>阻力</b>：全轴空气阻尼 {@link #DRAG}（明显大于普通粒子的 0.96），配合微重力
 *       形成"溅出即快速减速下坠"的观感，不做落地弹跳（与殉燃火星区分）。</li>
 * </ul>
 *
 * <p>贴图复用 {@code textures/nuclear/flare.png} 径向光斑，加法混合，不新增贴图资源
 * （与 {@code RVP_WreckSparkParticle} / {@code RVP_MchrFlareParticle} 同路径）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_ImpactSparkParticle extends SingleQuadParticle {

    /** 每 tick 全轴速度保留倍率；0.78 对应约 5 tick 内衰减到初速的 29%，阻力衰减明显。 */
    public static final double DRAG = 0.78D;

    /** 每 tick 竖直速度增量（正值 = 向下），单位格/tick²；给轻微下坠感。 */
    public static final double GRAVITY_PER_TICK = 0.02D;

    /** 锥形散布的最大半角（度）；spread=1 时达到该值，spread 越小越集中。 */
    public static final double MAX_CONE_HALF_ANGLE = 55.0D;

    /** 尺寸收缩起始比例：寿命剩余低于该比例开始同时缩小面片。 */
    private static final double SHRINK_START_RATIO = 0.6D;

    private final float startQuadSize;

    private RVP_ImpactSparkParticle(ClientLevel level, double x, double y, double z,
                                    double vx, double vy, double vz, float scale, int lifetime,
                                    float r, float g, float b) {
        super(level, x, y, z);
        // 寿命必须由构造器写入：基类缺省 0，首 tick 就会因 age >= lifetime 被移除
        this.lifetime = lifetime;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = r;
        this.gCol = g;
        this.bCol = b;
        this.quadSize = scale;
        this.startQuadSize = scale;
        this.alpha = 1.0f;
        // 不做碰撞：命中火星只活几 tick，碰撞求交的收益低于其开销，出界由寿命兜底
        this.hasPhysics = false;
        this.setSize(scale, scale);
        this.setPos(x, y, z);
    }

    /**
     * 生成一颗命中火星。
     *
     * @param options 命中火星数据（方向/速度/散布/寿命/颜色/尺寸），已由服务端按口径与弹速算好
     */
    public static void spawn(ParticleEngine engine, ClientLevel level,
                             double x, double y, double z, RVP_ImpactSparkOptions options) {
        if (engine == null || level == null || options == null) {
            return;
        }
        Vec3 axis = new Vec3(options.dirX(), options.dirY(), options.dirZ());
        // 在与基准方向垂直的平面上取随机方向，再按 spread 缩放到锥内，保证主体仍朝基准方向
        Vec3 helper = Math.abs(axis.y) < 0.9D ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 tangent1 = axis.cross(helper).normalize();
        Vec3 tangent2 = axis.cross(tangent1).normalize();
        double coneRadians = Math.toRadians(MAX_CONE_HALF_ANGLE) * options.spread();
        double lateral = Math.tan(coneRadians);
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double radius = lateral * Math.sqrt(level.random.nextDouble());
        Vec3 lateralVec = tangent1.scale(Math.cos(angle) * radius)
                .add(tangent2.scale(Math.sin(angle) * radius));
        // 速度大小在 0.6～1.0 间浮动，避免所有火星等速成"壳"
        double speedScale = 0.6D + 0.4D * level.random.nextDouble();
        Vec3 velocity = axis.add(lateralVec).normalize().scale(options.speed() * speedScale);
        engine.add(new RVP_ImpactSparkParticle(level, x, y, z,
                velocity.x, velocity.y, velocity.z, options.size(), options.lifetime(),
                options.r(), options.g(), options.b()));
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (++this.age >= this.lifetime) {
            this.remove();
            return;
        }
        // 全轴阻力衰减 + 微重力：先减速再下坠，符合"火星溅出即被空气拉住"的观感
        this.xd *= DRAG;
        this.yd *= DRAG;
        this.zd *= DRAG;
        this.yd -= GRAVITY_PER_TICK;
        this.move(this.xd, this.yd, this.zd);
        float remain = 1.0F - (float) this.age / this.lifetime;
        this.alpha = remain;
        this.quadSize = remain < SHRINK_START_RATIO
                ? this.startQuadSize * (remain / (float) SHRINK_START_RATIO)
                : this.startQuadSize;
    }

    @Override
    public void render(com.mojang.blaze3d.vertex.VertexConsumer buffer, net.minecraft.client.Camera camera,
                       float partialTicks) {
        Vec3 cam = camera.getPosition();
        Vec3 center = new Vec3(this.x - cam.x(), this.y - cam.y(), this.z - cam.z());
        float quad = this.quadSize;
        float alpha = this.alpha;
        float r = this.rCol;
        float g = this.gCol;
        float b = this.bCol;
        int light = this.getLightColor(partialTicks);
        buffer.vertex(center.x - quad, center.y - quad, center.z)
                .uv(0.0F, 1.0F).color(r, g, b, alpha).uv2(light).endVertex();
        buffer.vertex(center.x - quad, center.y + quad, center.z)
                .uv(0.0F, 0.0F).color(r, g, b, alpha).uv2(light).endVertex();
        buffer.vertex(center.x + quad, center.y + quad, center.z)
                .uv(1.0F, 0.0F).color(r, g, b, alpha).uv2(light).endVertex();
        buffer.vertex(center.x + quad, center.y - quad, center.z)
                .uv(1.0F, 1.0F).color(r, g, b, alpha).uv2(light).endVertex();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    /** {@code SingleQuadParticle} 四个 UV 抽象方法：本类自绘 render()，这里给整幅贴图占位。 */
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

    /** 加法混合渲染类型：绑定 {@code textures/nuclear/flare.png}，与殉燃火星同一路径。 */
    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, org.ywzj.rvp.RVP_MOD.modLocation("textures/nuclear/flare.png"));
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
            return "RVP_IMPACT_SPARK";
        }
    };

    /** 供 {@code RegisterParticleProvidersEvent#registerSpecial} 注册的工厂。 */
    @OnlyIn(Dist.CLIENT)
    public record Provider() implements net.minecraft.client.particle.ParticleProvider<RVP_ImpactSparkOptions> {
        @Override
        public net.minecraft.client.particle.Particle createParticle(RVP_ImpactSparkOptions options,
                                                                    ClientLevel level,
                                                                    double x, double y, double z,
                                                                    double xd, double yd, double zd) {
            return create(level, x, y, z, options);
        }
    }

    /** Provider 入口：与 {@link #spawn} 同一构造路径，仅供粒子工厂调用。 */
    private static net.minecraft.client.particle.Particle create(ClientLevel level,
                                                                double x, double y, double z,
                                                                RVP_ImpactSparkOptions options) {
        Vec3 axis = new Vec3(options.dirX(), options.dirY(), options.dirZ());
        Vec3 helper = Math.abs(axis.y) < 0.9D ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 tangent1 = axis.cross(helper).normalize();
        Vec3 tangent2 = axis.cross(tangent1).normalize();
        double coneRadians = Math.toRadians(MAX_CONE_HALF_ANGLE) * options.spread();
        double lateral = Math.tan(coneRadians);
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double radius = lateral * Math.sqrt(level.random.nextDouble());
        Vec3 lateralVec = tangent1.scale(Math.cos(angle) * radius)
                .add(tangent2.scale(Math.sin(angle) * radius));
        double speedScale = 0.6D + 0.4D * level.random.nextDouble();
        Vec3 velocity = axis.add(lateralVec).normalize().scale(options.speed() * speedScale);
        return new RVP_ImpactSparkParticle(level, x, y, z,
                velocity.x, velocity.y, velocity.z, options.size(), options.lifetime(),
                options.r(), options.g(), options.b());
    }
}
