package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;

/**
 * 可复用的近处烟团粒子。
 *
 * <p>击毁载具和损坏引擎都使用本类，差异只通过 {@link Style} 表达：普通样式保持
 * 原有击毁近处烟观感；瘫痪引擎样式在出生渲染帧显示整片橙色，下一 Tick 以底部为锚点
 * 向下收缩一部分后进入普通烟色。白色透明烟形贴图由代码染色，不新增资源文件。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_NearSmokeParticle extends SingleQuadParticle {

    /** 近处烟贴图横条的帧数：每帧 64×64。 */
    private static final int FRAME_COUNT = 16;
    /** 普通近处烟固定的东向微风速度，单位格/tick。 */
    private static final double EAST_BREEZE_SPEED = 0.057;
    /** 普通近处烟固定的微风摆动增量，单位格/tick²。 */
    private static final double BREEZE_SWAY_ACCELERATION = 0.0005;
    /** 橙色引擎烟动画的持续时间，单位 tick；1 表示只保留出生渲染帧。 */
    static final int ENGINE_HOT_PHASE_TICKS = 1;
    /** 橙色阶段结束后保留的纵向高度比例；底部保持不动，上缘向下收缩。 */
    static final float ENGINE_POST_HOT_HEIGHT_SCALE = 0.65f;
    /** 三种近处烟轮廓的独立渲染类型，复用现有白色透明贴图。 */
    private static final ParticleRenderType[] RENDER_TYPES = {
            renderType("textures/wreck_smoke/wreck_smoke_dense_round.png"),
            renderType("textures/wreck_smoke/wreck_smoke_broad_roll.png"),
            renderType("textures/wreck_smoke/wreck_smoke_ragged_cluster.png")
    };

    /** 近处烟的用途样式。 */
    public enum Style {
        /** 击毁载具和引擎受损档使用的普通深色近处烟。 */
        GENERIC,
        /** 引擎瘫痪档使用的橙色出生帧与向下收缩动画。 */
        ENGINE_DISABLED
    }

    /** 当前轮廓贴图的渲染类型。 */
    private final ParticleRenderType renderType;
    /** 粒子出生时的面片半宽，单位格。 */
    private final float initialSize;
    /** 烟团初始透明度；寿命末段按进度衰减。 */
    private final float initialAlpha;
    /** 烟团样式，决定出生颜色和纵向收缩行为。 */
    private final Style style;
    /** 烟团当前横条帧索引。 */
    private int frame;
    /** 每 tick 绕视线轴转动的弧度。 */
    private final float rollSpeed;
    /** 每枚烟团独立的微风相位。 */
    private final double breezePhase;
    /** 烟团当前纵向高度比例。 */
    private float heightScale = 1.0f;
    /** 烟团上一 tick 的纵向高度比例，供渲染插值。 */
    private float previousHeightScale = 1.0f;
    /** 普通烟色的红色通道。 */
    private final float normalRed;
    /** 普通烟色的绿色通道。 */
    private final float normalGreen;
    /** 普通烟色的蓝色通道。 */
    private final float normalBlue;
    /** 橙色出生帧的红色通道。 */
    private final float hotRed;
    /** 橙色出生帧的绿色通道。 */
    private final float hotGreen;
    /** 橙色出生帧的蓝色通道。 */
    private final float hotBlue;

    private RVP_NearSmokeParticle(ClientLevel level, double x, double y, double z,
                                  double vx, double vy, double vz, float size,
                                  int lifetime, int variant, Style style) {
        super(level, x, y, z);
        this.renderType = RENDER_TYPES[Math.floorMod(variant, RENDER_TYPES.length)];
        this.lifetime = Math.max(16, lifetime);
        this.style = style == null ? Style.GENERIC : style;
        this.initialSize = Math.max(0.15f, size);
        this.quadSize = this.initialSize;
        this.frame = 0;
        this.xd = EAST_BREEZE_SPEED + vx;
        this.yd = vy;
        this.zd = vz;
        this.breezePhase = level.random.nextDouble() * Mth.TWO_PI;
        this.hasPhysics = false;

        float shade = 0.08f + level.random.nextFloat() * 0.09f;
        this.normalRed = shade;
        this.normalGreen = shade + 0.005f;
        this.normalBlue = shade + 0.015f;
        this.hotRed = 1.0f;
        this.hotGreen = 0.34f + level.random.nextFloat() * 0.12f;
        this.hotBlue = 0.03f + level.random.nextFloat() * 0.06f;
        this.rCol = this.style == Style.ENGINE_DISABLED ? this.hotRed : this.normalRed;
        this.gCol = this.style == Style.ENGINE_DISABLED ? this.hotGreen : this.normalGreen;
        this.bCol = this.style == Style.ENGINE_DISABLED ? this.hotBlue : this.normalBlue;
        this.initialAlpha = 0.66f + level.random.nextFloat() * 0.18f;
        this.alpha = this.initialAlpha;
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.rollSpeed = (level.random.nextFloat() - 0.5f) * 0.025f;
    }

    /** 创建一枚可复用的近处烟团；调用方决定位置、寿命、轮廓和样式。 */
    public static RVP_NearSmokeParticle create(ClientLevel level, double x, double y, double z,
                                                double vx, double vy, double vz, float size,
                                                int lifetime, int variant, Style style) {
        return new RVP_NearSmokeParticle(level, x, y, z, vx, vy, vz,
                size, lifetime, variant, style);
    }

    /** 判断指定年龄是否仍处于橙色出生阶段，供单元测试锚定一帧语义。 */
    static boolean isEngineHotPhase(int age) {
        return age < ENGINE_HOT_PHASE_TICKS;
    }

    /** 计算引擎瘫痪动画在指定年龄的纵向高度比例。 */
    static float resolveEngineHeightScale(int age) {
        return isEngineHotPhase(age) ? 1.0f : ENGINE_POST_HOT_HEIGHT_SCALE;
    }

    /**
     * 计算击毁载具近处烟统一使用的面片尺寸，尺寸单位为格。
     *
     * <p>击毁载具、引擎受损档和引擎瘫痪档都调用本方法，确保三者只因 OBB 水平半径不同
     * 而有大小差异；最终尺寸仍钳制在击毁载具原有的 0.45～1.65 格范围。</p>
     *
     * @param radius OBB 水平半径，单位格
     * @param randomUnit [0, 1) 范围内的尺寸随机量
     * @return 近处烟面片半宽，单位格
     */
    public static float resolveWreckStyleSize(double radius, double randomUnit) {
        return (float) Mth.clamp(radius * (0.28 + randomUnit * 0.1), 0.45, 1.65);
    }

    /** 按完整贴图路径创建深度只测不写的半透明渲染类型。 */
    private static ParticleRenderType renderType(String texturePath) {
        // 调用本项目资源定位入口：把近处烟贴图绑定到 ywzj_rvp 命名空间。
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
                return "RVP_NEAR_SMOKE_" + texturePath;
            }
        };
    }

    @Override
    public ParticleRenderType getRenderType() {
        return this.renderType;
    }

    @Override
    public int getLightColor(float partialTick) {
        // 目的：保持击毁烟原有的天空光全亮表现，夜间仍能看见烟团轮廓。
        return 15 << 20;
    }

    @Override
    protected float getU0() {
        return (float) this.frame / FRAME_COUNT;
    }

    @Override
    protected float getU1() {
        return (float) (this.frame + 1) / FRAME_COUNT;
    }

    @Override
    protected float getV0() {
        return 0.0f;
    }

    @Override
    protected float getV1() {
        return 1.0f;
    }

    /**
     * 引擎瘫痪烟需要非等比纵向收缩；普通 {@link SingleQuadParticle} 的 quadSize 是等比的，
     * 因此这里沿相机面片纵轴单独缩放，并让下缘保持在粒子出生点。
     */
    @Override
    public void render(VertexConsumer buffer, Camera renderInfo, float partialTicks) {
        if (this.style != Style.ENGINE_DISABLED) {
            super.render(buffer, renderInfo, partialTicks);
            return;
        }
        Vec3 cameraPos = renderInfo.getPosition();
        float baseX = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cameraPos.x());
        float baseY = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cameraPos.y());
        float baseZ = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cameraPos.z());
        float size = this.getQuadSize(partialTicks);
        float interpolatedHeight = Mth.lerp(partialTicks, this.previousHeightScale, this.heightScale);
        float lower = -1.0f;
        float upper = lower + 2.0f * interpolatedHeight;
        // 复制相机旋转后叠加粒子自身滚转，避免修改渲染器持有的相机四元数。
        Quaternionf rotation = new Quaternionf(renderInfo.rotation());
        rotation.rotateZ(Mth.lerp(partialTicks, this.oRoll, this.roll));
        Vector3f[] corners = new Vector3f[]{
                new Vector3f(-1.0f, lower, 0.0f),
                new Vector3f(-1.0f, upper, 0.0f),
                new Vector3f(1.0f, upper, 0.0f),
                new Vector3f(1.0f, lower, 0.0f)};
        for (Vector3f corner : corners) {
            corner.rotate(rotation);
            corner.mul(size);
            corner.add(baseX, baseY, baseZ);
        }
        int light = this.getLightColor(partialTicks);
        buffer.vertex(corners[0].x(), corners[0].y(), corners[0].z()).uv(this.getU1(), this.getV1())
                .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
        buffer.vertex(corners[1].x(), corners[1].y(), corners[1].z()).uv(this.getU1(), this.getV0())
                .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
        buffer.vertex(corners[2].x(), corners[2].y(), corners[2].z()).uv(this.getU0(), this.getV0())
                .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
        buffer.vertex(corners[3].x(), corners[3].y(), corners[3].z()).uv(this.getU0(), this.getV1())
                .color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
        this.previousHeightScale = this.heightScale;
        ++this.age;
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }
        float progress = (float) this.age / this.lifetime;
        this.frame = Mth.clamp((int) (progress * FRAME_COUNT), 0, FRAME_COUNT - 1);
        this.quadSize = this.initialSize * (0.75f + progress * 1.25f);
        this.alpha = this.initialAlpha * (progress < 0.6f
                ? 1.0f : (1.0f - progress) / 0.4f);
        if (this.style == Style.ENGINE_DISABLED) {
            this.heightScale = resolveEngineHeightScale(this.age);
            this.rCol = this.normalRed;
            this.gCol = this.normalGreen;
            this.bCol = this.normalBlue;
        }
        this.roll += this.rollSpeed;
        double breezeWave = this.age * 0.06 + this.breezePhase;
        this.xd = EAST_BREEZE_SPEED + (this.xd - EAST_BREEZE_SPEED) * 0.96
                + Math.sin(breezeWave) * BREEZE_SWAY_ACCELERATION;
        this.zd = this.zd * 0.96 + Math.cos(breezeWave) * BREEZE_SWAY_ACCELERATION;
        this.yd = this.yd * 0.98 + 0.0015;
        // 调用本类粒子基类位移：维持原有近处烟的缓慢上浮与碰撞处理入口。
        this.move(this.xd, this.yd, this.zd);
    }
}
