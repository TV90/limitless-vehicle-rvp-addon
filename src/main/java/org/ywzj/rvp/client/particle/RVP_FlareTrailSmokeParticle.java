package org.ywzj.rvp.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 热诱弹（flare）拖尾的静态贴图烟粒子。
 *
 * <p>贴图取自 {@code textures/wreck_cookoff/particle/} 的同一套 128×128 白色 Alpha 轮廓
 * （由 {@link RVP_WreckCookoffParticleTextures} 统一加载，与车顶殉燃火柱、炮口灰烟共用
 * 资源清单与渲染批次），出生时固定一张图并按中性灰白染色。热诱弹本体是红色发光
 * billboard，烟只负责留下可见的飞行轨迹——因此不再使用原版
 * {@code CAMPFIRE_COSY_SMOKE}，观感与本项目殉燃烟一致。</p>
 *
 * <p>运动：在弹体当前位置出生后只带极小横向抖动，随即被阻尼拉停，留在原地缓慢上浮、
 * 膨胀、淡出；弹体继续前飞，粒子自然铺成拖尾。每枚粒子的贴图、尺寸、寿命、透明度、
 * 转角都在出生时随机，避免整齐的"串珠"感。</p>
 *
 * <p>粒子直接加入客户端粒子引擎（与殉燃火柱同一路径），不注册服务端粒子类型、
 * 不依赖粒子 JSON，也就不会向服务端引入客户端类或增加网络同步；热成像经
 * {@link RVP_ThermalParticleChannel} 自登记为可见热源（原先的原版营火烟由该通道的
 * 原版粒子白名单兜底显示，改为自定义粒子后必须显式登记，否则热成像下拖尾会消失）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_FlareTrailSmokeParticle extends SingleQuadParticle {

    /** 粒子寿命下限，单位 tick；过短会看不出"停住—膨胀—淡出"的拖尾过程。 */
    private static final int MIN_LIFETIME = 44;
    /** 粒子寿命上限，单位 tick；过长会在弹体后方堆成大烟墙。 */
    private static final int MAX_LIFETIME = 74;
    /** 出生面片尺寸下限，单位格；与热诱弹本体（haloScale 约 1 格）比例协调。 */
    private static final float MIN_SIZE = 0.55F;
    /** 出生面片尺寸上限，单位格。 */
    private static final float MAX_SIZE = 0.85F;
    /** 出生透明度下限；比原版营火烟更透明，避免遮挡热诱弹红色光点。 */
    private static final float MIN_ALPHA = 0.32F;
    /** 出生透明度上限。 */
    private static final float MAX_ALPHA = 0.52F;
    /** 灰白色调下限（0 为黑、1 为白）：中性灰白，与原版营火烟同色系。 */
    private static final float MIN_GREY = 0.62F;
    /** 灰白色调上限。 */
    private static final float MAX_GREY = 0.80F;
    /** 出生横向抖动速度，单位格/tick；让相邻烟团不完全重合，拖尾边缘更自然。 */
    private static final double SPAWN_JITTER = 0.012D;
    /** 水平阻尼：烟团出生后很快停在空气里。 */
    private static final double HORIZONTAL_DRAG = 0.90D;
    /** 竖直阻尼：上浮速度逐渐收敛到 {@link #UPDRAFT}。 */
    private static final double VERTICAL_DRAG = 0.94D;
    /** 目标上浮速度，单位格/tick。 */
    private static final double UPDRAFT = 0.006D;
    /** 出生尺寸倍率：面片从略小于初始尺寸开始膨胀。 */
    private static final float START_GROWTH = 0.80F;
    /** 寿命末的额外膨胀倍率：烟团整体放大到约初始尺寸的 1.85 倍。 */
    private static final float GROWTH_SPAN = 1.05F;
    /** 透明度保持满值的寿命比例；之后线性淡出到 0。 */
    private static final float FADE_START_PROGRESS = 0.55F;

    /** 粒子出生时固定的贴图索引；寿命内不换图（静态变体，不走动画帧）。 */
    private final int textureIndex;
    /** 粒子出生时的面片尺寸，单位格；之后按寿命曲线只做膨胀。 */
    private final float initialQuadSize;
    /** 粒子出生时的透明度；之后按寿命曲线衰减。 */
    private final float initialAlpha;
    /** 每 tick 绕视线轴转动的弧度；转角与角速度在出生时随机。 */
    private final float rollSpeed;

    private RVP_FlareTrailSmokeParticle(ClientLevel level, double x, double y, double z, float sizeScale) {
        super(level, x, y, z);
        RandomSource random = level.random;
        // 调用本项目贴图集入口：拖尾烟与殉燃火柱/炮口灰烟使用同一批贴图与同一批渲染批次。
        this.textureIndex = RVP_WreckCookoffParticleTextures.randomIndex(random);
        float scale = Float.isFinite(sizeScale) ? Mth.clamp(sizeScale, 0.25F, 3.0F) : 1.0F;
        this.initialQuadSize = Mth.lerp(random.nextFloat(), MIN_SIZE, MAX_SIZE) * scale;
        this.initialAlpha = Mth.lerp(random.nextFloat(), MIN_ALPHA, MAX_ALPHA);
        this.lifetime = MIN_LIFETIME + random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1);
        this.quadSize = this.initialQuadSize * START_GROWTH;
        this.alpha = this.initialAlpha;
        // 出生点就是弹体当前位置：烟团留在被抛下的位置，弹体前飞即形成拖尾。
        this.xd = (random.nextDouble() - 0.5D) * SPAWN_JITTER;
        this.yd = (random.nextDouble() - 0.5D) * SPAWN_JITTER;
        this.zd = (random.nextDouble() - 0.5D) * SPAWN_JITTER;
        float grey = Mth.lerp(random.nextFloat(), MIN_GREY, MAX_GREY);
        this.rCol = grey;
        this.gCol = grey;
        this.bCol = grey;
        this.roll = random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.rollSpeed = (random.nextFloat() - 0.5F) * 0.03F;
        this.hasPhysics = false;
        // 调用本项目热成像登记入口：原版营火烟改成本项目自定义粒子后，需自登记才能继续在热成像中显形。
        RVP_ThermalParticleChannel.register(this);
    }

    /**
     * 工厂：把一枚热诱弹拖尾烟加入客户端粒子引擎前的构造入口。
     *
     * @param level     当前客户端世界，不能为空
     * @param x         粒子出生 X 坐标
     * @param y         粒子出生 Y 坐标
     * @param z         粒子出生 Z 坐标
     * @param sizeScale 尺寸倍率；1 为默认观感，异常值钳制到 0.25～3
     * @return 尚未加入粒子引擎的粒子实例
     */
    public static RVP_FlareTrailSmokeParticle of(ClientLevel level, double x, double y, double z,
                                                 float sizeScale) {
        return new RVP_FlareTrailSmokeParticle(level, x, y, z, sizeScale);
    }

    /** 返回拖尾烟使用的贴图数量，供单元测试确认它复用殉燃贴图集。 */
    static int textureCount() {
        return RVP_WreckCookoffParticleTextures.count();
    }

    /** 透明度曲线：前 55% 寿命保持满值，之后线性淡出到 0。 */
    static float fadeFactor(float progress) {
        float clamped = Mth.clamp(progress, 0.0F, 1.0F);
        if (clamped <= FADE_START_PROGRESS) {
            return 1.0F;
        }
        return Mth.clamp((1.0F - clamped) / (1.0F - FADE_START_PROGRESS), 0.0F, 1.0F);
    }

    /** 尺寸曲线：随寿命从 0.80 倍线性膨胀到 1.85 倍。 */
    static float growthFactor(float progress) {
        return START_GROWTH + GROWTH_SPAN * Mth.clamp(progress, 0.0F, 1.0F);
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
        this.quadSize = this.initialQuadSize * growthFactor(progress);
        this.alpha = this.initialAlpha * fadeFactor(progress);
        this.roll += this.rollSpeed;
        // 水平速度快速衰减、竖直速度收敛到缓慢上浮：烟团停在被抛下的位置并继续上升。
        this.xd *= HORIZONTAL_DRAG;
        this.zd *= HORIZONTAL_DRAG;
        this.yd = this.yd * VERTICAL_DRAG + UPDRAFT;
        // 调用基类位移入口：按当前速度推进，保持与原版粒子一致的运动/包围盒更新。
        this.move(this.xd, this.yd, this.zd);
    }

    @Override
    public ParticleRenderType getRenderType() {
        // 调用本项目贴图集入口：返回该贴图的共享批次，避免多出一套重复批次。
        return RVP_WreckCookoffParticleTextures.renderType(this.textureIndex);
    }

    @Override
    public int getLightColor(float partialTick) {
        // 天空光全亮、不吃方块光：夜间也能看见拖尾轮廓（与本项目其它烟粒子同款亮度口径）。
        return 15 << 20;
    }

    /** 直接把整张 128×128 静态贴图映射到面片，不使用图集帧 UV。 */
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
}
