package org.ywzj.rvp.network.firesupport;

import io.netty.handler.codec.DecoderException;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportRequest;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionManager;
import org.ywzj.rvp.network.RVP_Network;

/** 客户端到服务端的新炮火呼叫选择；不携带任何可信派生值。 */
public record C2SRequestFireSupport(
        /** 客户端 profile revision。 */ long revision,
        /** 客户端选择的主手或副手。 */ InteractionHand hand,
        /** 客户端明确选择的 profile 资源 ID。 */ ResourceLocation profileId,
        /** profile 内弹种 ID。 */ String munitionId,
        /** profile 内射击模式 ID。 */ String fireModeId,
        /** profile 内打击预设 ID。 */ String patternId,
        /** 目标锚点 X。 */ double targetX,
        /** 目标锚点 Z。 */ double targetZ,
        /** 长轴/徐进方向，单位度。 */ double headingDegrees,
        /** 动态几何参数。 */ Map<String, Double> parameters,
        /** 新呼叫幂等 nonce。 */ UUID nonce) {
    /** 选择 ID 最大长度。 */ private static final int MAX_ID_LENGTH = 64;
    /** 网络层动态参数绝对上限。 */ private static final int MAX_PARAMETERS = 32;

    public C2SRequestFireSupport { parameters = Map.copyOf(parameters); }

    /** 把有界请求写入网络缓冲。 */
    public static void encode(C2SRequestFireSupport message, FriendlyByteBuf buffer) {
        buffer.writeLong(message.revision);
        buffer.writeEnum(message.hand);
        buffer.writeResourceLocation(message.profileId);
        buffer.writeUtf(message.munitionId, MAX_ID_LENGTH);
        buffer.writeUtf(message.fireModeId, MAX_ID_LENGTH);
        buffer.writeUtf(message.patternId, MAX_ID_LENGTH);
        buffer.writeDouble(message.targetX);
        buffer.writeDouble(message.targetZ);
        buffer.writeDouble(message.headingDegrees);
        if (message.parameters.size() > MAX_PARAMETERS) throw new IllegalArgumentException("炮火参数超过 32 项");
        buffer.writeVarInt(message.parameters.size());
        message.parameters.forEach((key, value) -> {
            buffer.writeUtf(key, MAX_ID_LENGTH);
            buffer.writeDouble(value);
        });
        buffer.writeUUID(message.nonce);
    }

    /** 解码并在分配集合前执行绝对数量限制。 */
    public static C2SRequestFireSupport decode(FriendlyByteBuf buffer) {
        try {
            long revision = buffer.readLong();
            InteractionHand hand = buffer.readEnum(InteractionHand.class);
            ResourceLocation profileId = buffer.readResourceLocation();
            String munition = buffer.readUtf(MAX_ID_LENGTH);
            String mode = buffer.readUtf(MAX_ID_LENGTH);
            String pattern = buffer.readUtf(MAX_ID_LENGTH);
            double x = buffer.readDouble();
            double z = buffer.readDouble();
            double heading = buffer.readDouble();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_PARAMETERS) throw new DecoderException("炮火参数超过 32 项");
            Map<String, Double> parameters = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String key = buffer.readUtf(MAX_ID_LENGTH);
                if (parameters.putIfAbsent(key, buffer.readDouble()) != null) throw new DecoderException("炮火参数键重复");
            }
            return new C2SRequestFireSupport(revision, hand, profileId, munition, mode, pattern, x, z, heading,
                    parameters, buffer.readUUID());
        } catch (DecoderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DecoderException("非法炮火支援请求", exception);
        }
    }

    /** 在服务端主线程执行权威验证并返回稳定结果。 */
    public static void handle(C2SRequestFireSupport message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() == null) return;
            // 调用本项目权威任务管理器：在服务端主线程复核请求并执行 nonce 幂等创建。
            RVP_FireSupportMissionManager.SubmissionResult result = RVP_FireSupportMissionManager.submit(
                    context.getSender(), new RVP_FireSupportRequest(message.revision, message.hand,
                            message.profileId, message.munitionId, message.fireModeId, message.patternId, message.targetX,
                            message.targetZ, message.headingDegrees, message.parameters, message.nonce));
            // 调用本项目网络通道，把权威接受/拒绝摘要只回复给请求玩家。
            RVP_Network.CHANNEL.sendTo(S2CFireSupportRequestResult.from(result),
                    context.getSender().connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        });
        context.setPacketHandled(true);
    }
}
