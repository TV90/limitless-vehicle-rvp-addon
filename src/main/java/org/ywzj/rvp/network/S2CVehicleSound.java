package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 默认导弹发射声音的兜底消息：只发给客户端没有载具实体的听者。
 *
 * <p>追踪到载具的玩家由客户端本地开火事件直接播放（零额外数据包）；此消息仅覆盖实体追踪范围外、
 * 无法收到本体开火广播的听者，因此不再携带殉燃心跳或绝对结束时刻。</p>
 *
 * @param dimension 声源所在维度，隔离切世界后的迟到消息
 * @param kind 发射声音分类，包含最远可听距离
 * @param vehicleId 载具 UUID，避免实体数字 ID 复用串车
 * @param entityId 载具数字 ID，仅用于实体已加载时读取跟随位置
 * @param weaponId 发射武器 ID，用于客户端保留表现文件的自定义声音
 * @param position 声源世界坐标，单位格；无需客户端加载载具或区块
 */
public record S2CVehicleSound(ResourceLocation dimension, Kind kind, UUID vehicleId, int entityId,
                              ResourceLocation weaponId, Vec3 position) {

    /** 发射使用的声音组；按载具类型分类，不判断具体武器 ID。 */
    public enum Kind {
        /** 地面、水面载具冷发射导弹；512 格内近音，其外远音，最远 2048 格。 */
        COLD_LAUNCH(2048),
        /** 地面、水面载具其余导弹；最远 2048 格。 */
        SURFACE_TO_AIR(2048),
        /** 固定翼、旋翼载具的所有导弹，包括对地导弹；最远 150 格。 */
        AIR2AIR(150);

        /** 当前声音的最远可听距离，单位格。 */
        private final int maximumDistance;

        Kind(int maximumDistance) {
            this.maximumDistance = maximumDistance;
        }

        /** 返回原版声音引擎使用的最远线性衰减距离，单位格。 */
        public int maximumDistance() {
            return maximumDistance;
        }

        /** 冷发射在 512 格边界内使用近音；其他声音不分近远。 */
        public boolean usesFarSound(double distance) {
            return this == COLD_LAUNCH && distance > 512.0D;
        }
    }

    /** 拒绝非法坐标和缺失武器 ID，防止异常消息进入资源解析与声音引擎。 */
    public S2CVehicleSound {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(weaponId, "weaponId");
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) {
            throw new IllegalArgumentException("Invalid vehicle sound");
        }
    }

    /** 编码声音所需的最小上下文，不传模型、武器数据全文或逐帧音频状态。 */
    public static void encode(S2CVehicleSound message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeEnum(message.kind);
        buffer.writeUUID(message.vehicleId);
        buffer.writeVarInt(message.entityId);
        buffer.writeResourceLocation(message.weaponId);
        buffer.writeDouble(message.position.x);
        buffer.writeDouble(message.position.y);
        buffer.writeDouble(message.position.z);
    }

    /** 解码后由构造器统一校验。 */
    public static S2CVehicleSound decode(FriendlyByteBuf buffer) {
        return new S2CVehicleSound(buffer.readResourceLocation(), buffer.readEnum(Kind.class),
                buffer.readUUID(), buffer.readVarInt(), buffer.readResourceLocation(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    /** 注册为客户端主线程处理器，公共网络类只调用双端安全桥。 */
    public static void handle(S2CVehicleSound message, Supplier<NetworkEvent.Context> supplier) {
        // 调用本项目客户端安全桥：专用服务端不会加载 Minecraft 或客户端声音类。
        org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptVehicleSound(message);
        supplier.get().setPacketHandled(true);
    }
}
