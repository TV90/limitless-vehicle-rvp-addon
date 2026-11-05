package org.ywzj.rvp.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffDynamics;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Snapshot;

import static org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter.*;

/**
 * 地面载具殉燃火柱的静态贴图粒子。
 *
 * <p>本类使用定向喷出、膨胀和淡出观感，
 * 每枚粒子出生时固定一张 128×128 贴图，生命周期内按出生参数改变位置、配色、尺寸和透明度。
 * 贴图是白色 Alpha 形状，由粒子颜色染成橙黄火焰。</p>
 *
 * <p>贴图集由 {@link RVP_WreckCookoffParticleTextures} 统一加载，本类只负责火柱行为；
 * 同一套贴图也被炮口灰烟与热诱弹拖尾烟（{@link RVP_FlareTrailSmokeParticle}）复用，
 * 资源清单与渲染批次不再各存一份。</p>
 *
 * <p>粒子由殉燃控制器直接加入客户端粒子引擎，不注册服务端粒子类型、不依赖粒子 JSON，
 * 因而不会向服务端引入客户端类，也不会增加网络同步协议。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckFlameParticle extends SingleQuadParticle {

    /** 当前粒子绑定的静态贴图索引；出生时固定，不随年龄变化。 */
    private final int textureIndex;
    /** 粒子出生时的面片尺寸，单位格。 */
    private final float initialQuadSize;
    /** 粒子出生时的最大透明度；生命周期末段按曲线衰减。 */
    private final float initialAlpha;
    /** 粒子出生时的滚转速度，单位弧度/tick。 */
    private final float rollSpeed;
    /** 有界车顶粒子的喷口原点；无边界喷燃粒子为 null。 */
    private final Vec3 travelOrigin;
    /** 有界车顶粒子的轴向单位向量；无边界喷燃粒子为 null。 */
    private final Vec3 travelAxis;
    /** 有界车顶粒子的轴向活动长度，单位格；支持循环或末端消散，无边界入口为正无穷。 */
    private final double maxTravelDistance;
    /** 本刻共享的不可变参数快照，已有粒子不随面板输入突然跳变。 */
    private final Snapshot settings;
    /** 原有逐片随机橙黄配色，分层配色为0时严格保留。 */
    private final Vec3 originalColor;

    private RVP_WreckFlameParticle(ClientLevel level, double x, double y, double z,
                                   double vx, double vy, double vz,
                                   float size, float alpha, int lifetime, int textureIndex,
                                   float red, float green, float blue,
                                   Vec3 travelOrigin, Vec3 travelAxis, double maxTravelDistance, Snapshot settings) {
        super(level, x, y, z);
        this.settings = settings;
        this.textureIndex = RVP_WreckCookoffParticleTextures.normalizeIndex(textureIndex);
        this.initialQuadSize = Math.max(0.08f, size);
        this.initialAlpha = Mth.clamp(alpha, 0.0f, 1.0f);
        this.rollSpeed = (level.random.nextFloat() - 0.5f) * 0.09f;
        this.travelOrigin = travelOrigin;
        this.travelAxis = travelAxis != null && travelAxis.lengthSqr() > 1.0E-8D
                ? travelAxis.normalize() : null;
        this.maxTravelDistance = Double.isFinite(maxTravelDistance)
                ? Math.max(0.05D, maxTravelDistance) : Double.POSITIVE_INFINITY;
        this.lifetime = clampLifetime(lifetime);
        this.quadSize = this.initialQuadSize * 0.72f;
        this.alpha = this.initialAlpha;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = Mth.clamp(red, 0.0f, 1.0f);
        this.gCol = Mth.clamp(green, 0.0f, 1.0f);
        this.bCol = Mth.clamp(blue, 0.0f, 1.0f);
        this.originalColor = new Vec3(this.rCol, this.gCol, this.bCol);
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.hasPhysics = false;
        // 调用出生外观计算，根部、中段和末端的首帧也使用相同配色与消散曲线。
        this.updateAppearance();
        // 调用本项目热成像登记入口：让静态喷燃粒子在本体热成像缓冲中作为热源重画。
        RVP_ThermalParticleChannel.register(this);
    }

    /**
     * 生成一枚沿火柱轴向喷出的静态贴图粒子。
     *
     * @param engine       目标粒子引擎；为空时忽略，避免客户端尚未完成初始化时崩溃
     * @param level        当前客户端世界
     * @param position     粒子出生位置
     * @param velocity     已包含轴向速度和横向扰动的初速度，单位格/tick
     * @param size         粒子初始目标尺寸，单位格
     * @param alpha        粒子出生透明度，范围 0～1
     * @param lifetime     粒子寿命，单位 tick
     * @param textureIndex 静态贴图索引
     * @param red          红色通道
     * @param green        绿色通道
     * @param blue         蓝色通道
     */
    public static void spawn(ParticleEngine engine, ClientLevel level, Vec3 position, Vec3 velocity,
                             float size, float alpha, int lifetime, int textureIndex,
                             float red, float green, float blue) {
        if (engine == null || level == null || position == null || velocity == null) {
            return;
        }
        // 调用本类构造器：固定贴图变体并把喷燃粒子加入客户端引擎。
        engine.add(new RVP_WreckFlameParticle(level, position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z, size, alpha, lifetime, textureIndex,
                red, green, blue, null, null, Double.POSITIVE_INFINITY, RVP_WreckCookoffSettings.snapshot()));
    }

    /**
     * 生成一枚带轴向边界的车顶喷燃贴图粒子。
     *
     * <p>高速粒子抵达目标高度后会从喷口端循环回收，避免高速位移造成贴图断层，
     * 同时保证粒子中心不会超出配置高度。</p>
     *
     * @param engine             目标粒子引擎
     * @param level              当前客户端世界
     * @param position           粒子出生位置
     * @param velocity            粒子初速度，单位格/tick
     * @param travelOrigin       车顶喷口原点
     * @param travelAxis         车顶喷燃轴向
     * @param maxTravelDistance  粒子中心允许达到的最大轴向距离，单位格
     * @param size               粒子初始尺寸，单位格
     * @param alpha              粒子出生透明度
     * @param lifetime           粒子生命周期，单位 tick
     * @param textureIndex       静态贴图索引
     * @param red                红色通道
     * @param green              绿色通道
     * @param blue               蓝色通道
     */
    public static void spawnBounded(ParticleEngine engine, ClientLevel level, Vec3 position, Vec3 velocity,
                                    Vec3 travelOrigin, Vec3 travelAxis, double maxTravelDistance,
                                    float size, float alpha, int lifetime, int textureIndex,
                                    float red, float green, float blue) {
        // 调用统一快照入口；正式批量生成走下面的重载，每刻只创建一份。
        spawnBounded(engine, level, position, velocity, travelOrigin, travelAxis, maxTravelDistance,
                size, alpha, lifetime, textureIndex, red, green, blue, RVP_WreckCookoffSettings.snapshot());
    }

    /** 正式殉燃批次共享快照，不为每张火焰贴图重复复制全部参数。 */
    public static void spawnBounded(ParticleEngine engine, ClientLevel level, Vec3 position, Vec3 velocity,
                                    Vec3 travelOrigin, Vec3 travelAxis, double maxTravelDistance,
                                    float size, float alpha, int lifetime, int textureIndex,
                                    float red, float green, float blue, Snapshot settings) {
        if (engine == null || level == null || position == null || velocity == null
                || travelOrigin == null || travelAxis == null) {
            return;
        }
        // 调用本类构造器：登记轴向边界，让高速车顶喷燃仍服从目标高度。
        engine.add(new RVP_WreckFlameParticle(level, position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z, size, alpha, lifetime, textureIndex,
                red, green, blue, travelOrigin, travelAxis, maxTravelDistance, settings));
    }

    /** 返回静态贴图数量，供控制器和单元测试共享边界。 */
    public static int textureCount() {
        return RVP_WreckCookoffParticleTextures.count();
    }

    /** 返回指定索引对应的静态贴图，供炮口灰烟/热诱弹拖尾烟复用同一套贴图资源。 */
    static ResourceLocation textureLocation(int textureIndex) {
        return RVP_WreckCookoffParticleTextures.location(textureIndex);
    }

    /** 把任意输入索引规范化到现有静态贴图范围。 */
    public static int normalizeTextureIndex(int textureIndex) {
        return RVP_WreckCookoffParticleTextures.normalizeIndex(textureIndex);
    }

    /** 粒子寿命下限；寿命过短时无法看出喷出、膨胀和淡出。 */
    public static int clampLifetime(int lifetime) {
        return Math.max(4, lifetime);
    }

    /** 把越过车顶目标高度的轴向距离折返到同一根火柱范围内，保持高速粒子连续铺满。 */
    static double wrapTravelDistance(double distance, double travelLength) {
        double safeLength = Double.isFinite(travelLength) ? Math.max(0.05D, travelLength) : 0.05D;
        if (!Double.isFinite(distance)) {
            return 0.0D;
        }
        double wrapped = distance - Math.floor(distance / safeLength) * safeLength;
        return Math.max(0.0D, Math.min(safeLength, wrapped));
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

        this.roll += this.rollSpeed;

        // 轻微阻尼配合沿车顶自身轴向漂移，载具侧翻时不会被世界 Y 轴重新拉回。
        this.xd *= 0.965;
        this.yd *= 0.975;
        this.zd *= 0.965;
        // 调用连续气流和近口/远端上浮过渡，默认仍沿喷口自身上方。
        Vec3 acceleration = RVP_WreckCookoffDynamics.flameAcceleration(new Vec3(this.x, this.y, this.z),
                this.travelAxis == null ? new Vec3(0, 1, 0) : this.travelAxis,
                this.axialProgress(), this.level.getGameTime(), this.settings);
        this.xd += acceleration.x;
        this.yd += acceleration.y;
        this.zd += acceleration.z;
        if (this.travelAxis == null) {
            // 调用基类碰撞位移入口：无边界喷燃粒子保持普通运动轨迹。
            this.move(this.xd, this.yd, this.zd);
        } else {
            // 调用本类有界位移入口：根据出生参数在末端消散或重新铺回柱内。
            this.moveWithinTravelBoundary();
        }
        // 调用统一外观曲线，让新位置对应的末端颜色与透明度一致。
        this.updateAppearance();
    }

    /** 沿喷口轴向的归一化进度；无边界入口以年龄推进配色。 */
    private double axialProgress() {
        return this.travelOrigin == null || this.travelAxis == null ? this.age / (double) this.lifetime
                : new Vec3(this.x, this.y, this.z).subtract(this.travelOrigin).dot(this.travelAxis) / this.maxTravelDistance;
    }

    /** 现有贴图只做程序配色、膨胀和淡出，不增加动画帧或替换共用资源。 */
    private void updateAppearance() {
        float progress = this.age / (float) this.lifetime;
        double axial = this.axialProgress();
        float grow = 0.72f + 0.48f * Mth.clamp(progress / 0.72f, 0.0f, 1.0f);
        this.quadSize = this.initialQuadSize * grow;
        // 调用共用空间/年龄曲线，同时更新颜色和不透明度。
        this.alpha = (float) (this.initialAlpha * RVP_WreckCookoffDynamics.flameFade(progress, axial, this.settings));
        Vec3 color = RVP_WreckCookoffDynamics.flameColor(axial, progress, this.originalColor, this.settings);
        this.rCol = (float) color.x;
        this.gCol = (float) color.y;
        this.bCol = (float) color.z;
    }

    /** 在车顶轴向范围内移动；循环和末端消散均由粒子自行处理。 */
    private void moveWithinTravelBoundary() {
        Vec3 current = new Vec3(this.x, this.y, this.z);
        Vec3 next = current.add(this.xd, this.yd, this.zd);
        double nextDistance = next.subtract(this.travelOrigin).dot(this.travelAxis);
        if (nextDistance >= 0.0D && nextDistance <= this.maxTravelDistance) {
            this.move(this.xd, this.yd, this.zd);
            return;
        }

        if (this.settings.enabled(NATURAL_TAIL)) {
            // 调用原版移除入口：末端自然消散模式只向外流动，不把老火焰搬回喷口。
            this.remove();
            return;
        }

        Vec3 relative = next.subtract(this.travelOrigin);
        Vec3 perpendicular = relative.subtract(this.travelAxis.scale(nextDistance));
        Vec3 capped = this.travelOrigin.add(perpendicular)
                .add(this.travelAxis.scale(wrapTravelDistance(nextDistance, this.maxTravelDistance)));
        this.setPos(capped.x, capped.y, capped.z);
        // 循环是同一粒子的重新铺设，不应把上一帧位置插值成贯穿整根火柱的逆向移动。
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
    }

    @Override
    public ParticleRenderType getRenderType() {
        // 调用本项目贴图集入口：与炮口灰烟/热诱弹拖尾烟共用同一张贴图的同一批次。
        return RVP_WreckCookoffParticleTextures.renderType(this.textureIndex);
    }

    @Override
    public int getLightColor(float partialTick) {
        // 全亮贴图：火焰自身发光，不依赖周围方块光照。
        return 0xF000F0;
    }

    /** 直接把整张 128×128 静态贴图映射到面片，不使用图集帧 UV。 */
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
