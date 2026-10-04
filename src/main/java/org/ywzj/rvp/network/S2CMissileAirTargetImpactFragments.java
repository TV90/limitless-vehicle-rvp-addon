package org.ywzj.rvp.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.impact.RVP_MissileAirTargetImpactFragmentSettings;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 服务端向客户端广播一次导弹命中离地空中目标后的视觉碎片事件。
 *
 * <p>消息只携带服务端快照的视觉参数；碎片不会作为服务端实体生成，也不会造成额外伤害。</p>
 *
 * @param dimension 事件所属维度
 * @param x 碎片生成中心 X
 * @param y 碎片生成中心 Y
 * @param z 碎片生成中心 Z
 * @param velocityX 碎片初始速度 X，单位格/tick
 * @param velocityY 碎片初始速度 Y，单位格/tick
 * @param velocityZ 碎片初始速度 Z，单位格/tick
 * @param count 碎片数量
 * @param seed 客户端确定性分散随机种子
 * @param startGameTime 服务端事件发生时间
 * @param fragmentConeHalfAngleDegrees 碎片相对导弹命中方向的圆锥最大偏转半角，单位度
 * @param damping 每 tick 速度保留比例
 * @param stopSpeed 视为速度归零的阈值，单位格/tick
 * @param spawnSpread 碎片出生位置横向分散距离，单位格
 * @param brownianStrength 布朗运动随机速度扰动，单位格/tick
 * @param smokeInterval 白烟生成间隔，单位 tick
 * @param smokeSize 白烟尺寸倍率
 * @param smokeLifetime 白烟寿命，单位 tick
 * @param smokeStartAlpha 白烟出生透明度
 * @param smokeEndAlpha 白烟寿命结束透明度
 * @param smokeLayers 每个轨迹采样点叠加的白烟贴图层数
 * @param smokePointSpacing 白烟轨迹采样点最大间距，单位格
 * @param smokeRotationDegrees 白烟贴图随机旋转范围，单位度
 * @param smokeLayerScaleVariance 白烟层间相对尺寸随机差异
 * @param smokeMaxPointsPerTick 单个碎片每次白烟生成最多补出的轨迹点数
 * @param maxLifetime 碎片异常寿命安全上限，单位 tick
 * @param broadcastRange 服务端广播距离，单位格
 */
public record S2CMissileAirTargetImpactFragments(
        ResourceLocation dimension,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        int count,
        long seed,
        long startGameTime,
        float fragmentConeHalfAngleDegrees,
        float damping,
        float stopSpeed,
        float spawnSpread,
        float brownianStrength,
        int smokeInterval,
        float smokeSize,
        int smokeLifetime,
        float smokeStartAlpha,
        float smokeEndAlpha,
        int smokeLayers,
        float smokePointSpacing,
        float smokeRotationDegrees,
        float smokeLayerScaleVariance,
        int smokeMaxPointsPerTick,
        int maxLifetime,
        double broadcastRange) {
    /** 网络消息允许的最大碎片数量，与服务端运行时参数上限保持一致。 */
    public static final int MAX_COUNT = RVP_MissileAirTargetImpactFragmentSettings.MAX_COUNT;
    /** 网络消息允许的最大安全寿命，与服务端运行时参数上限保持一致。 */
    public static final int MAX_LIFETIME = RVP_MissileAirTargetImpactFragmentSettings.MAX_MAX_LIFETIME;
    /** 网络消息允许的碎片圆锥最大偏转半角，单位度。 */
    public static final float MAX_FRAGMENT_CONE_HALF_ANGLE_DEGREES =
            RVP_MissileAirTargetImpactFragmentSettings.MAX_FRAGMENT_CONE_HALF_ANGLE_DEGREES;
    /** 网络消息允许的最大白烟寿命，与服务端运行时参数上限保持一致。 */
    public static final int MAX_SMOKE_LIFETIME = RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_LIFETIME;
    /** 网络消息允许的最大白烟起止透明度。 */
    public static final float MAX_SMOKE_ALPHA = RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_ALPHA;
    /** 网络消息允许的最大白烟尺寸，与服务端运行时参数上限保持一致。 */
    public static final float MAX_SMOKE_SIZE = RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_SIZE;
    /** 网络消息允许的最大烟雾间隔。 */
    public static final int MAX_SMOKE_INTERVAL = RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_INTERVAL;
    /** 网络消息允许的最大白烟贴图层数。 */
    public static final int MAX_SMOKE_LAYERS = RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_LAYERS;
    /** 网络消息允许的最大白烟轨迹采样点间距。 */
    public static final float MAX_SMOKE_POINT_SPACING =
            RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_POINT_SPACING;
    /** 网络消息允许的最大白烟随机旋转范围。 */
    public static final float MAX_SMOKE_ROTATION_DEGREES =
            RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_ROTATION_DEGREES;
    /** 网络消息允许的最大白烟层间尺寸差异。 */
    public static final float MAX_SMOKE_LAYER_SCALE_VARIANCE =
            RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_LAYER_SCALE_VARIANCE;
    /** 网络消息允许的单次白烟最大补点数。 */
    public static final int MAX_SMOKE_MAX_POINTS_PER_TICK =
            RVP_MissileAirTargetImpactFragmentSettings.MAX_SMOKE_MAX_POINTS_PER_TICK;
    /** 网络消息允许的最大广播距离。 */
    public static final double MAX_BROADCAST_RANGE = RVP_MissileAirTargetImpactFragmentSettings.MAX_BROADCAST_RANGE;

    public S2CMissileAirTargetImpactFragments {
        Objects.requireNonNull(dimension, "dimension");
        Vec3 position = new Vec3(x, y, z);
        Vec3 velocity = new Vec3(velocityX, velocityY, velocityZ);
        if (!isFinite(position) || !isFinite(velocity)
                || count < 1 || count > MAX_COUNT
                || !Float.isFinite(fragmentConeHalfAngleDegrees)
                || fragmentConeHalfAngleDegrees < 0.0F
                || fragmentConeHalfAngleDegrees > MAX_FRAGMENT_CONE_HALF_ANGLE_DEGREES
                || !Float.isFinite(damping) || damping < 0.0F || damping > 1.0F
                || !Float.isFinite(stopSpeed) || stopSpeed < 0.0F
                || !Float.isFinite(spawnSpread) || spawnSpread < 0.0F
                || !Float.isFinite(brownianStrength) || brownianStrength < 0.0F
                || smokeInterval < 1 || smokeInterval > MAX_SMOKE_INTERVAL
                || !Float.isFinite(smokeSize) || smokeSize < 0.0F || smokeSize > MAX_SMOKE_SIZE
                || smokeLifetime < 1 || smokeLifetime > MAX_SMOKE_LIFETIME
                || !Float.isFinite(smokeStartAlpha) || smokeStartAlpha < 0.0F
                || smokeStartAlpha > MAX_SMOKE_ALPHA
                || !Float.isFinite(smokeEndAlpha) || smokeEndAlpha < 0.0F
                || smokeEndAlpha > MAX_SMOKE_ALPHA
                || smokeLayers < 1 || smokeLayers > MAX_SMOKE_LAYERS
                || !Float.isFinite(smokePointSpacing) || smokePointSpacing <= 0.0F
                || smokePointSpacing > MAX_SMOKE_POINT_SPACING
                || !Float.isFinite(smokeRotationDegrees) || smokeRotationDegrees < 0.0F
                || smokeRotationDegrees > MAX_SMOKE_ROTATION_DEGREES
                || !Float.isFinite(smokeLayerScaleVariance) || smokeLayerScaleVariance < 0.0F
                || smokeLayerScaleVariance > MAX_SMOKE_LAYER_SCALE_VARIANCE
                || smokeMaxPointsPerTick < 1 || smokeMaxPointsPerTick > MAX_SMOKE_MAX_POINTS_PER_TICK
                || maxLifetime < 1 || maxLifetime > MAX_LIFETIME
                || !Double.isFinite(broadcastRange) || broadcastRange < 0.0D
                || broadcastRange > MAX_BROADCAST_RANGE) {
            throw new IllegalArgumentException("Invalid RVP missile impact fragment event");
        }
    }

    /** 编码视觉事件。 */
    public static void encode(S2CMissileAirTargetImpactFragments message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeDouble(message.x);
        buffer.writeDouble(message.y);
        buffer.writeDouble(message.z);
        buffer.writeDouble(message.velocityX);
        buffer.writeDouble(message.velocityY);
        buffer.writeDouble(message.velocityZ);
        buffer.writeVarInt(message.count);
        buffer.writeLong(message.seed);
        buffer.writeLong(message.startGameTime);
        buffer.writeFloat(message.fragmentConeHalfAngleDegrees);
        buffer.writeFloat(message.damping);
        buffer.writeFloat(message.stopSpeed);
        buffer.writeFloat(message.spawnSpread);
        buffer.writeFloat(message.brownianStrength);
        buffer.writeVarInt(message.smokeInterval);
        buffer.writeFloat(message.smokeSize);
        buffer.writeVarInt(message.smokeLifetime);
        buffer.writeFloat(message.smokeStartAlpha);
        buffer.writeFloat(message.smokeEndAlpha);
        buffer.writeVarInt(message.smokeLayers);
        buffer.writeFloat(message.smokePointSpacing);
        buffer.writeFloat(message.smokeRotationDegrees);
        buffer.writeFloat(message.smokeLayerScaleVariance);
        buffer.writeVarInt(message.smokeMaxPointsPerTick);
        buffer.writeVarInt(message.maxLifetime);
        buffer.writeDouble(message.broadcastRange);
    }

    /** 解码并校验视觉事件。 */
    public static S2CMissileAirTargetImpactFragments decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        double velocityX = buffer.readDouble();
        double velocityY = buffer.readDouble();
        double velocityZ = buffer.readDouble();
        int count = buffer.readVarInt();
        long seed = buffer.readLong();
        long startGameTime = buffer.readLong();
        float fragmentConeHalfAngleDegrees = buffer.readFloat();
        float damping = buffer.readFloat();
        float stopSpeed = buffer.readFloat();
        float spawnSpread = buffer.readFloat();
        float brownianStrength = buffer.readFloat();
        int smokeInterval = buffer.readVarInt();
        float smokeSize = buffer.readFloat();
        int smokeLifetime = buffer.readVarInt();
        float smokeStartAlpha = buffer.readFloat();
        float smokeEndAlpha = buffer.readFloat();
        int smokeLayers = buffer.readVarInt();
        float smokePointSpacing = buffer.readFloat();
        float smokeRotationDegrees = buffer.readFloat();
        float smokeLayerScaleVariance = buffer.readFloat();
        int smokeMaxPointsPerTick = buffer.readVarInt();
        int maxLifetime = buffer.readVarInt();
        double broadcastRange = buffer.readDouble();
        try {
            return new S2CMissileAirTargetImpactFragments(
                    dimension, x, y, z, velocityX, velocityY, velocityZ, count, seed, startGameTime,
                    fragmentConeHalfAngleDegrees, damping, stopSpeed, spawnSpread, brownianStrength,
                    smokeInterval, smokeSize,
                    smokeLifetime, smokeStartAlpha, smokeEndAlpha, smokeLayers, smokePointSpacing,
                    smokeRotationDegrees,
                    smokeLayerScaleVariance, smokeMaxPointsPerTick, maxLifetime, broadcastRange);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid RVP missile impact fragment event", exception);
        }
    }

    /** 在网络主线程切换后交给公共客户端消费端口。 */
    public static void handle(S2CMissileAirTargetImpactFragments message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RVP_MissileAirTargetImpactFragmentEndpoint.accept(message));
        context.setPacketHandled(true);
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
