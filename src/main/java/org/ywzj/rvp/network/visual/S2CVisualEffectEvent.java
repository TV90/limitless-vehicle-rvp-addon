package org.ywzj.rvp.network.visual;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEndpoint;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 当前 schema 的通用服务端到客户端视觉事件消息。
 *
 * @param effectType 客户端工厂类型
 * @param preset 客户端预设资源 ID
 * @param canonicalPresetDataJson 规范化后的预设覆盖 JSON
 * @param dimension 事件维度 ID
 * @param position 服务端权威爆心
 * @param baseExplosionRadius 最终爆炸半径（格）
 * @param scale 视觉尺寸倍率
 * @param density 服务端允许的最大视觉密度
 * @param durationTicks 持续时间覆盖（tick），{@code -1} 表示使用预设
 * @param broadcastRange 网络广播距离（格）
 * @param seed 客户端确定性演算种子
 * @param startGameTime 服务端开始世界时间（tick）
 * @param sound 是否允许声音
 * @param flash 是否允许闪光
 * @param shake 是否允许镜头震动
 * @param experimentalDynamicParticleBudget 是否启用实验性动态粒子预算
 */
public record S2CVisualEffectEvent(
        ResourceLocation effectType,
        ResourceLocation preset,
        String canonicalPresetDataJson,
        ResourceLocation dimension,
        Vec3 position,
        float baseExplosionRadius,
        float scale,
        float density,
        int durationTicks,
        double broadcastRange,
        long seed,
        long startGameTime,
        boolean sound,
        boolean flash,
        boolean shake,
        boolean experimentalDynamicParticleBudget
) {
    /** 当前消息载荷 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;
    /** 声音开关位。 */
    private static final int FLAG_SOUND = 1;
    /** 闪光开关位。 */
    private static final int FLAG_FLASH = 1 << 1;
    /** 震动开关位。 */
    private static final int FLAG_SHAKE = 1 << 2;
    /** 实验性动态粒子预算开关位。 */
    private static final int FLAG_EXPERIMENTAL_DYNAMIC_PARTICLE_BUDGET = 1 << 3;

    public S2CVisualEffectEvent {
        Objects.requireNonNull(effectType, "effectType");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(canonicalPresetDataJson, "canonicalPresetDataJson");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
    }

    /** 从公共领域事件创建不持有客户端对象的网络 DTO。 */
    public S2CVisualEffectEvent(RVP_VisualEffectEvent event) {
        this(event.effectType(), event.preset(), event.canonicalPresetDataJson(), event.dimension().location(),
                event.position(), event.baseExplosionRadius(), event.scale(), event.density(), event.durationTicks(),
                event.broadcastRange(), event.seed(), event.startGameTime(), event.sound(), event.flash(), event.shake(),
                event.experimentalDynamicParticleBudget());
    }

    public static void encode(S2CVisualEffectEvent message, FriendlyByteBuf buffer) {
        buffer.writeByte(SCHEMA_VERSION);
        buffer.writeResourceLocation(message.effectType);
        buffer.writeResourceLocation(message.preset);
        byte[] presetData = message.canonicalPresetDataJson.getBytes(StandardCharsets.UTF_8);
        if (presetData.length > RVP_VisualEffectEvent.MAX_PRESET_DATA_BYTES) {
            throw new IllegalArgumentException("RVP visual preset_data exceeds 8 KiB");
        }
        buffer.writeVarInt(presetData.length);
        buffer.writeBytes(presetData);
        buffer.writeResourceLocation(message.dimension);
        buffer.writeDouble(message.position.x);
        buffer.writeDouble(message.position.y);
        buffer.writeDouble(message.position.z);
        buffer.writeFloat(message.baseExplosionRadius);
        buffer.writeFloat(message.scale);
        buffer.writeFloat(message.density);
        buffer.writeVarInt(message.durationTicks);
        buffer.writeDouble(message.broadcastRange);
        buffer.writeLong(message.seed);
        buffer.writeLong(message.startGameTime);
        int flags = (message.sound ? FLAG_SOUND : 0)
                | (message.flash ? FLAG_FLASH : 0)
                | (message.shake ? FLAG_SHAKE : 0)
                | (message.experimentalDynamicParticleBudget
                        ? FLAG_EXPERIMENTAL_DYNAMIC_PARTICLE_BUDGET : 0);
        buffer.writeByte(flags);
    }

    public static S2CVisualEffectEvent decode(FriendlyByteBuf buffer) {
        int schemaVersion = buffer.readUnsignedByte();
        if (schemaVersion != SCHEMA_VERSION) {
            throw new DecoderException("Unsupported RVP visual effect schema version: " + schemaVersion);
        }
        ResourceLocation effectType = buffer.readResourceLocation();
        ResourceLocation preset = buffer.readResourceLocation();
        int presetDataLength = buffer.readVarInt();
        if (presetDataLength < 0 || presetDataLength > RVP_VisualEffectEvent.MAX_PRESET_DATA_BYTES) {
            throw new DecoderException("RVP visual preset_data exceeds 8 KiB");
        }
        String presetData = buffer.readCharSequence(presetDataLength, StandardCharsets.UTF_8).toString();
        ResourceLocation dimension = buffer.readResourceLocation();
        Vec3 position = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        float radius = buffer.readFloat();
        float scale = buffer.readFloat();
        float density = buffer.readFloat();
        int durationTicks = buffer.readVarInt();
        double broadcastRange = buffer.readDouble();
        long seed = buffer.readLong();
        long startGameTime = buffer.readLong();
        int flags = buffer.readUnsignedByte();
        validateNumbers(position, radius, scale, density, durationTicks, broadcastRange);
        return new S2CVisualEffectEvent(
                effectType,
                preset,
                presetData,
                dimension,
                position,
                radius,
                scale,
                density,
                durationTicks,
                broadcastRange,
                seed,
                startGameTime,
                (flags & FLAG_SOUND) != 0,
                (flags & FLAG_FLASH) != 0,
                (flags & FLAG_SHAKE) != 0,
                (flags & FLAG_EXPERIMENTAL_DYNAMIC_PARTICLE_BUDGET) != 0);
    }

    public static void handle(S2CVisualEffectEvent message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 调用公共消费端口，把消息交给物理客户端安装的 dispatcher，避免网络类链接客户端类型。
            RVP_VisualEffectEndpoint.accept(message.toDomainEvent());
        });
        context.setPacketHandled(true);
    }

    private RVP_VisualEffectEvent toDomainEvent() {
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimension);
        return new RVP_VisualEffectEvent(
                effectType, preset, canonicalPresetDataJson, dimensionKey, position, baseExplosionRadius,
                scale, density, durationTicks, broadcastRange, seed, startGameTime, sound, flash, shake,
                experimentalDynamicParticleBudget);
    }

    private static void validateNumbers(
            Vec3 position,
            float radius,
            float scale,
            float density,
            int durationTicks,
            double broadcastRange) {
        boolean valid = Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                && Float.isFinite(radius) && radius >= 0.0F
                && Float.isFinite(scale) && scale >= 0.0F
                && Float.isFinite(density) && density >= 0.0F
                && (durationTicks == -1 || durationTicks >= 0)
                && Double.isFinite(broadcastRange) && broadcastRange >= 0.0D;
        if (!valid) {
            throw new DecoderException("Invalid numeric value in RVP visual effect event");
        }
    }
}
