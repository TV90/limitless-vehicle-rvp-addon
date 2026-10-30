package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 服务端确认的近身划空或方块命中声；不依赖弹体在客户端仍然存在。
 *
 * @param dimension 声音所在维度，防止切换世界后播放迟到消息
 * @param kind 声音用途及其音频组
 * @param position 声源世界坐标，单位格
 * @param variant 音频组内从 0 开始的编号，由服务端随机选择
 */
public record S2CSmallCaliberSound(ResourceLocation dimension, Kind kind, Vec3 position, int variant) {

    /** 小口径声音用途以及现有音频文件数量。 */
    public enum Kind {
        /** 弹体飞过玩家附近，使用 bullet_incoming 的六个样本。 */
        INCOMING(6),
        /** 弹体命中方块，使用 bullet_dirt 的十一个样本。 */
        BLOCK_IMPACT(11);

        /** 当前声音用途可选的样本数量。 */
        private final int variantCount;

        Kind(int variantCount) {
            this.variantCount = variantCount;
        }

        /** 返回当前声音用途的样本数量。 */
        public int variantCount() {
            return variantCount;
        }
    }

    /** 检查消息数据，避免异常数值进入客户端声音引擎。 */
    public S2CSmallCaliberSound {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(kind, "kind");
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z) || variant < 0 || variant >= kind.variantCount()) {
            throw new IllegalArgumentException("Invalid small-caliber sound");
        }
    }

    /** 编码维度、声音种类、声源位置及样本编号。 */
    public static void encode(S2CSmallCaliberSound message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeEnum(message.kind);
        buffer.writeDouble(message.position.x);
        buffer.writeDouble(message.position.y);
        buffer.writeDouble(message.position.z);
        buffer.writeVarInt(message.variant);
    }

    /** 解码时复用构造器校验。 */
    public static S2CSmallCaliberSound decode(FriendlyByteBuf buffer) {
        return new S2CSmallCaliberSound(buffer.readResourceLocation(), buffer.readEnum(Kind.class),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readVarInt());
    }

    /** 在客户端主线程通过双端安全桥播放，专用服务端不加载客户端声音类。 */
    public static void handle(S2CSmallCaliberSound message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // 调用本项目客户端安全桥，转交服务端确认的声音消息。
        context.enqueueWork(() -> org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptSmallCaliberSound(message));
        context.setPacketHandled(true);
    }
}
