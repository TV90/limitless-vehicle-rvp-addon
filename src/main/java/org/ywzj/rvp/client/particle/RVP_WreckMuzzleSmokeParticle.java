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
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 地面载具殉燃炮口的灰黑烟粒子（<b>自绘管线</b>）。
 *
 * <p>复用车顶殉燃的静态喷火贴图，出生时随机选择一张白色 Alpha 轮廓并通过粒子颜色染成灰黑色；
 * 粒子沿炮管方向离口后，速度持续逼近可调的世界竖直上浮速度，形成向上飘散趋势。</p>
 *
 * <p><b>为什么自绘（2026-10-07 三轮实机迭代的结论）</b>：本烟与殉燃黑烟分属不同自定义
 * ParticleRenderType 批次，1.20.1 ParticleEngine 对自定义批不做距离排序、批间先后与距离无关
 * 且单次运行内稳定——在引擎内写深度则同批烟块互相深度剔除（多块叠加的增浓失效、烟变稀透出
 * 背景）且后画的载具半透明切片被剔穿；不写深度则黑烟批后画时整批盖住白烟。本类因此脱离
 * ParticleEngine，由 {@link RVP_WreckMuzzleSmokeSelfRenderer} 在 AFTER_PARTICLES 阶段
 * （恒晚于黑烟批）按距离排序自绘，深度只测不写：层间叠加正确、载具按 alpha 混合软遮挡、
 * 白烟恒在黑烟之前。已知边界：自绘粒子不进热成像白热通道（自定义批时代亦不被提白，无回归）；
 * 贴图分组间顺序与距离无关（同色系白烟观感影响小）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckMuzzleSmokeParticle extends SingleQuadParticle {

    /** 静态贴图渲染类型（深度只测不写）——仅作误入 ParticleEngine 时的兜底批，正常路径走自绘。 */
    private static final ParticleRenderType[] RENDER_TYPES = createRenderTypes();

    /** 粒子绑定的静态贴图索引（自绘渲染时按贴图分组绘制）。 */
    private final int textureIndex;
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

    RVP_WreckMuzzleSmokeParticle(ClientLevel level, Vec3 position, Vec3 velocity,
                                         float size, float alpha, int lifetime, int textureIndex,
                                         double updraft, double spread, float grey) {
        super(level, position.x, position.y, position.z);
        this.textureIndex = normalizeTextureIndex(textureIndex);
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
        double current = Double.isFinite(currentVelocity) ? Math.max(0.0D, currentVelocity) : 0.0D;
        return target + (current - target) * 0.98D;
    }

    /** 返回本粒子绑定的贴图索引，供自绘管理器按贴图分组绘制。 */
    int textureIndex() {
        return this.textureIndex;
    }

    /** 返回本粒子是否已结束（寿命耗尽或被移除），供自绘管理器从列表摘除。 */
    boolean isGone() {
        return this.removed;
    }

    /** 返回本粒子到相机的距离平方（自绘排序用，避免跨包访问坐标字段）。 */
    double distanceSqrTo(Camera camera) {
        Vec3 cameraPos = camera.getPosition();
        double dx = this.x - cameraPos.x;
        double dy = this.y - cameraPos.y;
        double dz = this.z - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 创建车顶喷火贴图对应的深度只测不写渲染类型（兜底批；排序自绘见 RVP_WreckMuzzleSmokeSelfRenderer）。 */
    private static ParticleRenderType renderType(ResourceLocation texture) {
        // 调用本项目车顶喷燃贴图入口：炮口只复用资源并通过颜色染成灰黑，不复制或覆盖贴图。
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
        return RENDER_TYPES[this.textureIndex];
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
            // 调用基类移除入口：寿命耗尽后由自绘管理器从列表摘除。
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
