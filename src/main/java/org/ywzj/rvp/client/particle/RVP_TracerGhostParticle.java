package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.all.RVP_TracerGhostOptions;

/**
 * 近距命中曳光残影：与 {@code VehicleProjectileRenderLogic} 实体曳光同款的
 * 速度对齐发光箱体（同色、同宽、同长），加色混合 + 只测深度不写深度，
 * 3 tick 线性淡出。替代原 DustParticleOptions 色尘补渲的观感方案。
 */
@OnlyIn(Dist.CLIENT)
public class RVP_TracerGhostParticle extends Particle {

    /** 残影存活 tick 数；覆盖一次瞄准-淡出的人眼留影即可，过长会与后续曳光弹混淆。 */
    private static final int LIFETIME_TICKS = 3;

    private final Vec3 direction;
    private final float length;
    private final float halfWidth;

    RVP_TracerGhostParticle(ClientLevel level, double x, double y, double z, RVP_TracerGhostOptions options) {
        super(level, x, y, z);
        this.direction = new Vec3(options.dirX(), options.dirY(), options.dirZ());
        this.length = options.length();
        this.halfWidth = options.width() * 0.5F;
        this.rCol = options.r();
        this.gCol = options.g();
        this.bCol = options.b();
        this.alpha = 1.0F;
        this.lifetime = LIFETIME_TICKS;
        this.hasPhysics = false;
        this.gravity = 0.0F;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        this.alpha = 1.0F - (float) this.age / this.lifetime;
    }

    @Override
    public void render(VertexConsumer buffer, Camera renderInfo, float partialTicks) {
        Vec3 camera = renderInfo.getPosition();
        Vec3 center = new Vec3(this.x - camera.x(), this.y - camera.y(), this.z - camera.z());
        Vec3 dir = this.direction;
        Vec3 up = Math.abs(dir.y) < 0.99D ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 side1 = dir.cross(up).normalize().scale(this.halfWidth);
        Vec3 side2 = dir.cross(side1).normalize().scale(this.halfWidth);
        Vec3 front = center.add(dir.scale(this.length * 0.5D));
        Vec3 back = center.subtract(dir.scale(this.length * 0.5D));
        float r = this.rCol * this.alpha;
        float g = this.gCol * this.alpha;
        float b = this.bCol * this.alpha;
        Vec3 f1 = front.add(side1).add(side2);
        Vec3 f2 = front.add(side1).subtract(side2);
        Vec3 f3 = front.subtract(side1).subtract(side2);
        Vec3 f4 = front.subtract(side1).add(side2);
        Vec3 b1 = back.add(side1).add(side2);
        Vec3 b2 = back.add(side1).subtract(side2);
        Vec3 b3 = back.subtract(side1).subtract(side2);
        Vec3 b4 = back.subtract(side1).add(side2);
        // 加色混合下绕向不影响可见性；2 端盖 + 4 侧壁共 6 面。
        quad(buffer, f1, f2, f3, f4, r, g, b);
        quad(buffer, b1, b2, b3, b4, r, g, b);
        quad(buffer, f1, f2, b2, b1, r, g, b);
        quad(buffer, f2, f3, b3, b2, r, g, b);
        quad(buffer, f3, f4, b4, b3, r, g, b);
        quad(buffer, f4, f1, b1, b4, r, g, b);
    }

    private static void quad(VertexConsumer buffer, Vec3 v1, Vec3 v2, Vec3 v3, Vec3 v4,
                             float r, float g, float b) {
        buffer.vertex(v1.x, v1.y, v1.z).color(r, g, b, 1.0F).endVertex();
        buffer.vertex(v2.x, v2.y, v2.z).color(r, g, b, 1.0F).endVertex();
        buffer.vertex(v3.x, v3.y, v3.z).color(r, g, b, 1.0F).endVertex();
        buffer.vertex(v4.x, v4.y, v4.z).color(r, g, b, 1.0F).endVertex();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return TRACER_GHOST_RENDER_TYPE;
    }

    /** 加色混合的 POSITION_COLOR 自绘批：深度只测不写（弹道被地形遮挡、不污染后续粒子深度）。 */
    private static final ParticleRenderType TRACER_GHOST_RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(com.mojang.blaze3d.vertex.BufferBuilder bufferBuilder,
                          net.minecraft.client.renderer.texture.TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        }

        @Override
        public void end(Tesselator tesselator) {
            tesselator.end();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
        }

        @Override
        public String toString() {
            return "rvp:tracer_ghost";
        }
    };

    /** 供 {@code RegisterParticleProvidersEvent#registerSpecial} 注册的工厂。 */
    @OnlyIn(Dist.CLIENT)
    public record Provider() implements ParticleProvider<RVP_TracerGhostOptions> {
        @Override
        public Particle createParticle(RVP_TracerGhostOptions options, ClientLevel level,
                                       double x, double y, double z, double xd, double yd, double zd) {
            return new RVP_TracerGhostParticle(level, x, y, z, options);
        }
    }
}
