package org.ywzj.rvp.client.particle;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 地面载具殉燃炮口的灰黑烟粒子。
 *
 * <p>复用车顶殉燃的静态喷火贴图，出生时随机选择一张白色 Alpha 轮廓并通过粒子颜色染成灰黑色；
 * 粒子沿炮管方向离口后，速度持续逼近可调的世界竖直上浮速度，形成向上飘散趋势。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckMuzzleSmokeParticle extends SingleQuadParticle {

    /** 复用车顶殉燃的全部静态喷火贴图，不新增资源文件。 */
    private static final ParticleRenderType[] RENDER_TYPES = createRenderTypes();

    /** 当前烟粒子绑定的静态贴图渲染类型。 */
    private final ParticleRenderType renderType;
    /** 粒子出生时的面片尺寸，单位格。 */
    private final float initialQuadSize;
    /** 粒子出生时的透明度上限。 */
    private final float initialAlpha;
    /** 粒子目标竖直上浮速度，单位格/tick。 */
    private final double updraft;
    /** 粒子水平摆动速度增量，单位格/tick。 */
    private final double spread;
    /** 每 tick 绕视线轴转动的弧度。 */
    private final float rollSpeed;
    /** 每枚烟团独立的水平摆动相位。 */
    private final double swayPhase;
    private RVP_WreckMuzzleSmokeParticle(ClientLevel level, Vec3 position, Vec3 velocity,
                                         float size, float alpha, int lifetime, int textureIndex,
                                         double updraft, double spread, float grey) {
        super(level, position.x, position.y, position.z);
        this.renderType = RENDER_TYPES[normalizeTextureIndex(textureIndex)];
        this.initialQuadSize = Math.max(0.1F, size);
        this.initialAlpha = Mth.clamp(alpha, 0.0F, 1.0F);
        this.lifetime = Math.max(4, lifetime);
        this.quadSize = this.initialQuadSize * 0.7F;
        this.alpha = this.initialAlpha;
        this.xd = velocity.x;
        this.yd = velocity.y;
        this.zd = velocity.z;
        this.updraft = Math.max(0.0D, Math.min(1.0D, updraft));
        this.spread = Math.max(0.0D, Math.min(0.25D, spread));
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.rollSpeed = (level.random.nextFloat() - 0.5F) * 0.025F;
        this.swayPhase = level.random.nextDouble() * Mth.TWO_PI;
        float shade = Mth.clamp(grey * (0.85F + level.random.nextFloat() * 0.30F), 0.0F, 1.0F);
        this.rCol = shade;
        this.gCol = Mth.clamp(shade + 0.005F, 0.0F, 1.0F);
        this.bCol = Mth.clamp(shade + 0.015F, 0.0F, 1.0F);
        this.hasPhysics = false;
    }

    /**
     * 加入一枚炮口殉燃灰烟粒子。
     *
     * @param engine 客户端粒子引擎
     * @param level 客户端世界
     * @param position 粒子出生位置
     * @param velocity 粒子初速度，单位格/tick
     * @param size 初始面片尺寸，单位格
     * @param alpha 初始透明度
     * @param lifetime 生命周期，单位 tick
     * @param textureIndex 贴图变体索引
     * @param updraft 目标竖直上浮速度，单位格/tick
     * @param spread 水平摆动速度增量，单位格/tick
     * @param grey 灰度，0 为黑色，1 为白色
     */
    public static void spawn(ParticleEngine engine, ClientLevel level, Vec3 position, Vec3 velocity,
                             float size, float alpha, int lifetime, int textureIndex,
                             double updraft, double spread, float grey) {
        if (engine == null || level == null || position == null || velocity == null) {
            return;
        }
        // 调用本类构造器：把炮口灰烟加入客户端粒子引擎，不触碰服务端载具状态。
        engine.add(new RVP_WreckMuzzleSmokeParticle(level, position, velocity, size, alpha,
                lifetime, textureIndex, updraft, spread, grey));
    }

    /** 返回与车顶殉燃相同的静态贴图数量，供控制器和单元测试共享边界。 */
    static int textureCount() {
        return RENDER_TYPES.length;
    }

    /** 把任意输入索引规范化到车顶殉燃贴图范围。 */
    static int normalizeTextureIndex(int textureIndex) {
        return Math.floorMod(textureIndex, RENDER_TYPES.length);
    }

    /** 让竖直速度平滑逼近目标上浮速度，避免炮口烟出生时突然改变方向。 */
    static double approachUpdraft(double currentVelocity, double targetUpdraft) {
        double target = Double.isFinite(targetUpdraft) ? Math.max(0.0D, Math.min(1.0D, targetUpdraft)) : 0.0D;
        double current = Double.isFinite(currentVelocity) ? currentVelocity : 0.0D;
        return target + (current - target) * 0.98D;
    }

    /**
     * 创建炮口烟渲染类型：<b>写深度</b>的半透明（对齐殉燃烟/爆炸烟 MCHR 语义）。
     * <p>原因：本类与殉燃黑烟（RVP_MchrSmokeRenderType.RENDER_TYPE）分属两个自定义
     * ParticleRenderType 批次，1.20.1 ParticleEngine 对自定义批不做距离排序、批间先后与
     * 距离无关——若本烟只测不写（depthMask(false)），殉燃黑烟批后画时深度缓冲里没有本烟，
     * 远处黑烟会整批盖住近处炮口烟（2026-10-07 实机症状）。写深度后无论两批谁先画，
     * 深度缓冲都按真实前后正确互挡。</p>
     * <p>已知代价（与 MCHR 爆炸烟现状同款）：渐隐期仍以全深度挡住后面物体；对同帧后画的
     * 半透明切片（如载具 entityTranslucent 部件）按深度剔除。</p>
     */
    private static ParticleRenderType renderType(ResourceLocation texture) {
        // 调用本项目车顶喷燃贴图入口：炮口只复用资源并通过颜色染成灰黑，不复制或覆盖贴图。
        return new ParticleRenderType() {
            @Override
            public void begin(BufferBuilder builder, TextureManager textureManager) {
                RenderSystem.depthMask(true);
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
                return "RVP_WRECK_MUZZLE_SMOKE_" + texture;
            }
        };
    }

    /** 创建与车顶喷燃完全相同的静态贴图集合。 */
    private static ParticleRenderType[] createRenderTypes() {
        int textureCount = RVP_WreckFlameParticle.textureCount();
        ParticleRenderType[] types = new ParticleRenderType[textureCount];
        for (int i = 0; i < textureCount; i++) {
            // 调用本项目车顶喷燃贴图定位入口：确保两种殉燃效果永远使用同一组资源。
            types[i] = renderType(RVP_WreckFlameParticle.textureLocation(i));
        }
        return types;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return this.renderType;
    }

    @Override
    public int getLightColor(float partialTick) {
        // 灰烟保持可见，但不产生方块照明。
        return 15 << 20;
    }

    @Override
    protected float getU0() {
        return 0.0F;
    }

    @Override
    protected float getU1() {
        return 1.0F;
    }

    @Override
    protected float getV0() {
        return 0.0F;
    }

    @Override
    protected float getV1() {
        return 1.0F;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
        ++this.age;
        if (this.age >= this.lifetime) {
            // 调用基类移除入口：寿命耗尽后从客户端粒子引擎摘除。
            this.remove();
            return;
        }
        float progress = this.age / (float) this.lifetime;
        this.quadSize = this.initialQuadSize * (0.7F + 1.0F * progress);
        this.alpha = this.initialAlpha * Mth.clamp(progress < 0.58F
                ? 1.0F : (1.0F - progress) / 0.42F, 0.0F, 1.0F);
        this.roll += this.rollSpeed;
        double wave = this.age * 0.07D + this.swayPhase;
        this.xd = this.xd * 0.965D + Math.sin(wave) * this.spread;
        this.zd = this.zd * 0.965D + Math.cos(wave) * this.spread;
        this.yd = approachUpdraft(this.yd, this.updraft);
        // 调用本类粒子基类位移：维持炮口烟离管后向上漂移和水平轻微摆动。
        this.move(this.xd, this.yd, this.zd);
    }
}
