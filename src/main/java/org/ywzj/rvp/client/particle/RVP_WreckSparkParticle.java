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
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.RVP_MOD;

/**
 * RVP 地面载具殉燃的"重物理火星"：贴图仍复用现有 {@code textures/nuclear/flare.png} 径向光斑
 * （由本类自定义 {@link ParticleRenderType} 直接绑定并自算 0～1 UV，复用
 * {@code RVP_MchrFlareParticle} 已示的"不新增贴图/不依赖粒子定义 JSON 与图集"路径），
 * 但运动完全由本类实现，不再使用原版 {@code ParticleTypes.FLAME}：
 * <ul>
 *   <li>竖向受重力加速、落地按竖向恢复系数反弹，水平按摩擦衰减；</li>
 *   <li>位移仍调用基类 {@code Particle.move}——它已按运动扫掠求交并只推进到接触点，
 *       比自写 AABB 判定更准；本类只在其后读取 {@code onGround} 做速度反射，
 *       因此不会出现"离地还有一段就被弹起"或"位置越过接触面"的错位；</li>
 *   <li>横向撞墙由基类处理（清零该轴并可能置 {@code stoppedByCollision}），本类不再单独判墙、
 *       也不会误吞水平速度；</li>
 *   <li>弹跳达上限后停跳、由体积与透明度收尾淡出。</li>
 * </ul>
 *
 * <p><b>关于 {@code getParticleGroup()} 与渲染裁剪</b>：本类不覆写 {@code getParticleGroup()}，
 * 因此粒子引擎的"每组数量上限"对它不生效；可见性由调用方
 * {@code RVP_WreckCookoffBudget.distanceScale} 的距离档位与火柱段数间接控制，
 * 与既有 MCHR 火星（同样走 {@code ParticleEngine.add} 直加）保持一致。</p>
 *
 * <p><b>盒与坐标的一致性</b>：{@code Particle.setSize} 重建包围盒时沿用旧的 {@code minY}、
 * 不重新居中 Y，{@code Particle.setPos} 才会把盒重新对齐到坐标。构造里先 {@code setSize}
 * 再 {@code setPos}，是为了让"可见面片中心 = 盒中心 = 出生点、盒底 = 出生点 − 半个身位"，
 * 否则粒子一出生就处于触地状态。</p>
 *
 * 所有物理量都是 {@code static} 常量，碰撞响应是纯函数，便于自动测试直接引用断言。
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckSparkParticle extends SingleQuadParticle {

    /** 每 tick 竖直速度增量（正值 = 向下加速），单位格/tick²；0.04 与爆炸飞溅火星同级。 */
    public static final double GRAVITY_PER_TICK = 0.04;
    /** 落地竖向恢复系数：反弹后的上升速度 = 触地时下落速度 × 0.35（每次弹跳约衰减到 1/3 高）。 */
    public static final double GROUND_RESTITUTION = 0.35;
    /** 每次触地时水平分量的保留倍率，使落地后火星迅速趴住不再远滑。 */
    public static final double GROUND_FRICTION = 0.6;
    /** 竖直反弹的最低触发速度，单位格/tick；低于它不再起跳，避免在方块表面抖动。 */
    public static final double MIN_BOUNCE_SPEED = 0.05;
    /** 最多落地弹跳次数，之后只滑行不再起跳（对应"弹 2～3 次后淡出"）。 */
    public static final int MAX_BOUNCES = 3;
    /** 每 tick 水平空气阻尼倍率（竖向不受阻，保证重力干净积分）。 */
    public static final double AIR_DRAG = 0.96;
    /** 寿命进入收尾的比例：保留最后 25% 的寿命用于 alpha 与体积淡出。 */
    public static final double FADE_START_RATIO = 0.75;
    /** 最小有效寿命，单位 tick；低于它看不出下坠与弹跳。 */
    public static final int MIN_LIFETIME = 24;
    /** 殉燃火星面片半宽，单位格；比火柱更小，避免加法混合糊成一片。 */
    public static final float SPARK_QUAD_SIZE = 0.16f;
    /** 沿轴抛射速度下限，单位格/tick。 */
    public static final double LAUNCH_SPEED_MIN = 0.45;
    /** 沿轴抛射速度的随机增量，单位格/tick；与下限共同给出 0.45～0.95。 */
    public static final double LAUNCH_SPEED_RANGE = 0.5;
    /** 火星寿命的倒数分布基准，单位 tick；配合 16/(u·0.8+0.2) 给出 16～80。 */
    public static final double LIFETIME_BASE = 16.0;

    /** 火星暖黄 tint，与 {@code RVP_MchrFlareParticle} 的曳光火星同源配色。 */
    private static final float TINT_R = 1.0f;
    private static final float TINT_G = 0.9f;
    private static final float TINT_B = 0.6f;

    /**
     * 独立加法混合渲染类型：绑定现有 {@code textures/nuclear/flare.png} 径向渐变光斑。
     * 与 {@code RVP_MchrFlareParticle} 的渲染类型分开持有，便于将来单独调火星观感互不影响。
     */
    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder builder, TextureManager textureManager) {
            // 与 MCHR 火星同款加法混合：flare.png 边缘为黑，加法下天然无边框
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, RVP_MOD.modLocation("textures/nuclear/flare.png"));
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
            return "RVP_WRECK_SPARK";
        }
    };

    /** 出生时的面片半宽，单位格；淡出期按剩余寿命收缩，用于换算当前尺寸。 */
    private final float startQuadSize;
    /** 出生时的透明度；淡出期按剩余寿命线性衰减。 */
    private final float startAlpha;
    /** 本次落地后已发生的弹跳次数，达 {@link #MAX_BOUNCES} 后不再起跳。 */
    private int bounces;

    private RVP_WreckSparkParticle(ClientLevel level, double x, double y, double z,
                                   double vx, double vy, double vz, float scale, int lifetime) {
        super(level, x, y, z);
        // 寿命必须由构造器写入：基类缺省为 0，首 tick 就会因 age >= lifetime 被移除
        this.lifetime = clampLifetime(lifetime);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = TINT_R;
        this.gCol = TINT_G;
        this.bCol = TINT_B;
        this.alpha = 1.0f;
        this.startAlpha = 1.0f;
        this.quadSize = scale;
        this.startQuadSize = scale;
        // 调用基类尺寸与坐标设置：setSize 会沿用旧 minY 重建盒，必须紧跟 setPos 重新居中，
        // 否则盒底会落在出生点上，粒子一出生就被判为触地
        this.setSize(scale, scale);
        this.setPos(x, y, z);
        this.hasPhysics = true;
        // 热成像自登记：殉燃火星在本体热成像视角下作为热源重画发白，与曳光火星一致
        RVP_ThermalParticleChannel.register(this);
    }

    /**
     * 生成一枚殉燃火星：初速为炮口/舱盖轴方向乘以沿轴抛射速度，竖直分量是沿轴分量而非额外上抛，
     * 因此"从柱身喷出 → 受重力下坠 → 落地弹跳"是同一个物理过程。
     *
     * @param engine    目标粒子引擎；为 {@code null} 时直接忽略（客户端未就绪）
     * @param dirX      出口轴方向 X 分量，单位向量分量
     * @param axisSpeed 沿轴抛射速度，单位格/tick；由调用方按出口类别给值
     */
    public static void spawn(ParticleEngine engine, ClientLevel level,
                             double x, double y, double z,
                             double dirX, double dirY, double dirZ,
                             double axisSpeed, float scale) {
        if (engine == null) {
            return;
        }
        // 调用本类寿命掷点，先得到寿命再构造，避免出现寿命缺省导致粒子立即消失
        int lifetime = rollLifetime(level.random);
        // 调用 RVP 火星构造，竖直分量继承轴方向，落地弹跳交给本类 tick
        engine.add(new RVP_WreckSparkParticle(level, x, y, z,
                dirX * axisSpeed, dirY * axisSpeed, dirZ * axisSpeed, scale, lifetime));
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        ++this.age;
        if (this.age >= this.lifetime) {
            // 调用基类移除入口，寿命耗尽即从粒子引擎摘除
            this.remove();
            return;
        }
        // 本 tick 先结算全部受力：重力 + 水平空气阻尼，再用末速度位移
        this.yd -= GRAVITY_PER_TICK;
        this.xd *= AIR_DRAG;
        this.zd *= AIR_DRAG;
        // 记录位移前速度：基类 move 会把被挡住的速度清零，反射必须用这一步的撞击速度
        double impactYd = this.yd;
        // 调用基类碰撞位移：它按运动扫掠求交并只推进到接触点，遇方块/水会清零对应轴速度
        this.move(this.xd, this.yd, this.zd);
        this.applyGroundContact(impactYd);
        this.updateFade();
    }

    /**
     * 触地响应：基类 {@code move} 已把位置贴到接触点，本方法只把被清零的竖直速度按
     * {@link #GROUND_RESTITUTION} 翻成上升速度，并按 {@link #GROUND_FRICTION} 吃掉水平分量。
     *
     * @param impactYd 位移前的竖直速度；正 = 向上，负 = 向下
     */
    private void applyGroundContact(double impactYd) {
        // 调用本类纯函数碰撞响应，便于自动测试直接断言
        Contact contact = respondToContact(this.xd, impactYd, this.zd, this.bounces, this.onGround);
        this.xd = contact.xd();
        this.yd = contact.yd();
        this.zd = contact.zd();
        this.bounces = contact.bounces();
    }

    /**
     * 落地响应的纯数学部分，不接触世界状态，供自动测试直接覆盖。
     *
     * <p>只有基类 {@code move} 判定为"向下被挡住"（{@code onGround}）时才结算弹跳；
     * 未触地时竖直速度交还给本 tick 的积分值（含重力），因此自由飞行段是完全干净的抛物线。</p>
     *
     * @param inXd      本 tick 位移前的 X 速度，单位格/tick
     * @param inImpactYd 本 tick 位移前的 Y 速度（正 = 向上、负 = 向下），单位格/tick
     * @param inZd      本 tick 位移前的 Z 速度，单位格/tick
     * @param inBounces 已发生的落地弹跳次数
     * @param onGround  基类碰撞位移是否判定为触地
     * @return 响应后的三轴速度与弹跳计数
     */
    public static Contact respondToContact(double inXd, double inImpactYd, double inZd,
                                           int inBounces, boolean onGround) {
        if (!onGround) {
            return new Contact(inXd, inImpactYd, inZd, inBounces);
        }
        if (inImpactYd < 0.0 && -inImpactYd >= MIN_BOUNCE_SPEED && inBounces < MAX_BOUNCES) {
            // 触地：下落按恢复系数翻成上升，水平分量被地面摩擦吃掉，弹跳计数递增
            return new Contact(inXd * GROUND_FRICTION, -inImpactYd * GROUND_RESTITUTION,
                    inZd * GROUND_FRICTION, inBounces + 1);
        }
        // 下落速度低于起跳阈值或已达弹跳上限：竖直停住，保留水平滑行
        return new Contact(inXd, 0.0, inZd, inBounces);
    }

    /** 碰撞响应结果：响应后的三轴速度与累计落地弹跳次数。 */
    public record Contact(double xd, double yd, double zd, int bounces) {}

    /** 寿命末端线性淡出并轻微收小，避免火星在空气里瞬间消失。 */
    private void updateFade() {
        float progress = this.age / (float) this.lifetime;
        if (progress <= FADE_START_RATIO) {
            this.quadSize = this.startQuadSize;
            this.alpha = this.startAlpha;
            return;
        }
        float fade = (progress - (float) FADE_START_RATIO) / (1.0f - (float) FADE_START_RATIO);
        this.quadSize = this.startQuadSize * (1.0f - 0.5f * fade);
        this.alpha = this.startAlpha * (1.0f - fade);
    }

    /** 全亮自发光：光斑贴图自带径向衰减，无需方块光。 */
    @Override
    public int getLightColor(float partialTick) {
        return 0xF000F0;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    /** 光斑贴图整张映射到 0～1 UV，不使用图集切分。 */
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

    /** 火星寿命下限保护：由调用方传入的寿命不足时抬到可看出弹跳的长度。 */
    public static int clampLifetime(int lifetime) {
        return Math.max(MIN_LIFETIME, lifetime);
    }

    /**
     * 掷出本次火星的沿轴抛射速度，单位格/tick。
     *
     * @param randomUnit01 调用方提供的 [0,1) 随机数，便于自动测试直接断言两端取值
     */
    public static double launchSpeed(double randomUnit01) {
        // 调用本类速度收敛，非有限的随机输入不会产生 NaN 速度
        return clampSpeed(LAUNCH_SPEED_MIN + randomUnit01 * LAUNCH_SPEED_RANGE);
    }

    /** 抛射速度的合法性收敛：非有限值回落到下限；下限与 {@link #MIN_BOUNCE_SPEED} 无关。 */
    private static double clampSpeed(double speed) {
        return Double.isFinite(speed) && speed >= LAUNCH_SPEED_MIN ? speed : LAUNCH_SPEED_MIN;
    }

    /**
     * 掷出本次火星的寿命，单位 tick；沿用原版"倒数分布"形状（多数偏短、少数很长），
     * 结果抬到 {@link #MIN_LIFETIME}，保证长于"抛射速度上限 / 重力"所需的下落 tick 数。
     *
     * <p>寿命不随距离缩短：远处本来就会因预算分到更少火星，若再缩寿命，火星会在落地前淡出，
     * 看不到下坠与弹跳。</p>
     *
     * @param random 客户端随机源
     */
    public static int rollLifetime(RandomSource random) {
        return clampLifetime((int) (LIFETIME_BASE / (random.nextDouble() * 0.8 + 0.2)));
    }
}
