package org.ywzj.rvp.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
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
import org.ywzj.rvp.RVP_MOD;

/**
 * 地面载具殉燃火柱的静态贴图粒子。
 *
 * <p>本类使用定向喷出、膨胀和淡出观感，
 * 每枚粒子出生时固定一张 128×128 贴图，生命周期内只改变位置、尺寸和透明度。
 * 贴图是白色 Alpha 形状，由粒子颜色染成橙黄火焰。</p>
 *
 * <p>粒子由殉燃控制器直接加入客户端粒子引擎，不注册服务端粒子类型、不依赖粒子 JSON，
 * 因而不会向服务端引入客户端类，也不会增加网络同步协议。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RVP_WreckFlameParticle extends SingleQuadParticle {

    /** 殉燃喷燃贴图所在的资源目录。 */
    private static final String TEXTURE_DIRECTORY = "textures/wreck_cookoff/particle/";

    /** 所有可用的静态喷燃贴图路径；索引在粒子出生时固定，不随年龄变化。 */
    private static final String[] TEXTURE_NAMES = {
            "flame_somke_01.png",
            "flame_somke_02.png",
            "flame_somke_03.png",
            "flame_somke_04.png",
            "flame_somke_05.png",
            "flame_somke_06.png",
            "flame_somke_07.png",
            "flame_somke_08.png",
            "flame_somke_09.png",
            "flame_somke_010.png",
            "flame_somke_011.png",
            "flame_somke_012.png",
            "flame_somke_013.png",
            "flame_somke_014.png",
            "flame_somke_015.png",
            "flame_somke_016.png",
    };

    /** 每张静态贴图对应的独立渲染批次，避免把不同纹理强行拼进动画图集。 */
    private static final ParticleRenderType[] RENDER_TYPES = createRenderTypes();

    /** 当前粒子绑定的静态贴图渲染批次。 */
    private final ParticleRenderType renderType;
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
    /** 有界车顶粒子的轴向循环长度，单位格；无边界喷燃粒子为正无穷。 */
    private final double maxTravelDistance;

    private RVP_WreckFlameParticle(ClientLevel level, double x, double y, double z,
                                   double vx, double vy, double vz,
                                   float size, float alpha, int lifetime, int textureIndex,
                                   float red, float green, float blue,
                                   Vec3 travelOrigin, Vec3 travelAxis, double maxTravelDistance) {
        super(level, x, y, z);
        this.renderType = RENDER_TYPES[normalizeTextureIndex(textureIndex)];
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
        this.roll = level.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.hasPhysics = false;
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
                red, green, blue, null, null, Double.POSITIVE_INFINITY));
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
        if (engine == null || level == null || position == null || velocity == null
                || travelOrigin == null || travelAxis == null) {
            return;
        }
        // 调用本类构造器：登记轴向边界，让高速车顶喷燃仍服从目标高度。
        engine.add(new RVP_WreckFlameParticle(level, position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z, size, alpha, lifetime, textureIndex,
                red, green, blue, travelOrigin, travelAxis, maxTravelDistance));
    }

    /** 返回静态贴图数量，供控制器和单元测试共享边界。 */
    public static int textureCount() {
        return TEXTURE_NAMES.length;
    }

    /** 返回指定索引对应的车顶喷燃贴图，供炮口灰烟复用同一套贴图资源。 */
    static ResourceLocation textureLocation(int textureIndex) {
        return RVP_MOD.modLocation(TEXTURE_DIRECTORY + TEXTURE_NAMES[normalizeTextureIndex(textureIndex)]);
    }

    /** 把任意输入索引规范化到现有静态贴图范围。 */
    public static int normalizeTextureIndex(int textureIndex) {
        return Math.floorMod(textureIndex, TEXTURE_NAMES.length);
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

        float progress = this.age / (float) this.lifetime;
        float fade = progress < 0.58f ? 1.0f : (1.0f - progress) / 0.42f;
        float grow = 0.72f + 0.48f * Mth.clamp(progress / 0.72f, 0.0f, 1.0f);
        this.quadSize = this.initialQuadSize * grow;
        this.alpha = this.initialAlpha * Mth.clamp(fade, 0.0f, 1.0f);
        this.roll += this.rollSpeed;

        // 轻微阻尼配合世界向上漂移，保留轴向喷出感而不形成静止贴图柱。
        this.xd *= 0.965;
        this.yd = this.yd * 0.975 + 0.0025;
        this.zd *= 0.965;
        if (this.travelAxis == null) {
            // 调用基类碰撞位移入口：无边界喷燃粒子保持普通运动轨迹。
            this.move(this.xd, this.yd, this.zd);
        } else {
            // 调用本类有界位移入口：车顶粒子越过目标高度时截停并进入短淡出，避免高度漂移。
            this.moveWithinTravelBoundary();
        }
    }

    /** 在车顶轴向循环范围内移动；循环细节不外露，避免把客户端粒子状态扩散到控制器。 */
    private void moveWithinTravelBoundary() {
        Vec3 current = new Vec3(this.x, this.y, this.z);
        Vec3 next = current.add(this.xd, this.yd, this.zd);
        double nextDistance = next.subtract(this.travelOrigin).dot(this.travelAxis);
        if (nextDistance >= 0.0D && nextDistance <= this.maxTravelDistance) {
            this.move(this.xd, this.yd, this.zd);
            return;
        }

        Vec3 relative = next.subtract(this.travelOrigin);
        Vec3 perpendicular = relative.subtract(this.travelAxis.scale(nextDistance));
        Vec3 capped = this.travelOrigin.add(perpendicular)
                .add(this.travelAxis.scale(wrapTravelDistance(nextDistance, this.maxTravelDistance)));
        this.setPos(capped.x, capped.y, capped.z);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return this.renderType;
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

    /** 创建全部静态贴图的渲染类型。 */
    private static ParticleRenderType[] createRenderTypes() {
        ParticleRenderType[] types = new ParticleRenderType[TEXTURE_NAMES.length];
        for (int i = 0; i < TEXTURE_NAMES.length; i++) {
            types[i] = createRenderType(textureLocation(i));
        }
        return types;
    }

    /** 创建单张喷燃贴图的半透明渲染批次。 */
    private static ParticleRenderType createRenderType(ResourceLocation texture) {
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
                return "RVP_WRECK_FLAME_" + texture;
            }
        };
    }
}
