package org.ywzj.rvp.network.firesupport;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportEndReason;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 新呼叫或停火请求的即时权威结果。 */
public record S2CFireSupportRequestResult(
        /** 对应操作 nonce。 */ UUID nonce,
        /** 是否为停火操作结果。 */ boolean ceaseFire,
        /** 是否被服务端接受。 */ boolean accepted,
        /** 稳定原因枚举。 */ RVP_FireSupportEndReason reason,
        /** 接受时任务 UUID。 */ UUID missionId,
        /** 新呼叫权威随机种子。 */ long authoritativeSeed,
        /** 新呼叫规范化参数。 */ Map<String, Double> parameters,
        /** 新呼叫的呼叫截止 Tick。 */ long callDeadlineTick,
        /** 新呼叫首发计划 Tick。 */ long firstRoundTick,
        /** 新呼叫末发计划 Tick；停火结果中为停火生效 Tick。 */ long lastRoundTick) {
    /** S2C 规范化参数硬上限。 */ private static final int MAX_PARAMETERS = 32;
    /** 参数键最大长度。 */ private static final int MAX_KEY_LENGTH = 64;

    public S2CFireSupportRequestResult { parameters = Map.copyOf(parameters); }

    /** 从任务管理器的新呼叫结果构造消息。 */
    public static S2CFireSupportRequestResult from(RVP_FireSupportMissionManager.SubmissionResult result) {
        return new S2CFireSupportRequestResult(result.nonce(), false, result.accepted(), result.reason(),
                result.missionId(), result.authoritativeSeed(), result.parameters(), result.callDeadlineTick(),
                result.firstRoundTick(), result.lastRoundTick());
    }

    /** 从停火结果构造消息；lastRoundTick 复用为权威停火生效 Tick。 */
    public static S2CFireSupportRequestResult fromCeaseFire(UUID nonce, UUID missionId,
                                                            RVP_FireSupportMissionManager.CeaseFireResult result) {
        return new S2CFireSupportRequestResult(nonce, true, result.accepted(), result.reason(), missionId,
                0, Map.of(), 0, 0, result.effectiveTick());
    }

    /** 写入有界结果。 */
    public static void encode(S2CFireSupportRequestResult message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.nonce);
        buffer.writeBoolean(message.ceaseFire);
        buffer.writeBoolean(message.accepted);
        buffer.writeEnum(message.reason);
        buffer.writeUUID(message.missionId);
        buffer.writeLong(message.authoritativeSeed);
        if (message.parameters.size() > MAX_PARAMETERS) throw new IllegalArgumentException("结果参数超过 32 项");
        buffer.writeVarInt(message.parameters.size());
        message.parameters.forEach((key, value) -> {
            buffer.writeUtf(key, MAX_KEY_LENGTH);
            buffer.writeDouble(value);
        });
        buffer.writeLong(message.callDeadlineTick);
        buffer.writeLong(message.firstRoundTick);
        buffer.writeLong(message.lastRoundTick);
    }

    /** 解码有界结果。 */
    public static S2CFireSupportRequestResult decode(FriendlyByteBuf buffer) {
        UUID nonce = buffer.readUUID();
        boolean cease = buffer.readBoolean();
        boolean accepted = buffer.readBoolean();
        RVP_FireSupportEndReason reason = buffer.readEnum(RVP_FireSupportEndReason.class);
        UUID mission = buffer.readUUID();
        long seed = buffer.readLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_PARAMETERS) throw new DecoderException("结果参数超过 32 项");
        Map<String, Double> parameters = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) parameters.put(buffer.readUtf(MAX_KEY_LENGTH), buffer.readDouble());
        return new S2CFireSupportRequestResult(nonce, cease, accepted, reason, mission, seed, parameters,
                buffer.readLong(), buffer.readLong(), buffer.readLong());
    }

    /** 经公共端口交给客户端状态，不直接引用 Screen 或 Minecraft。 */
    public static void handle(S2CFireSupportRequestResult message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RVP_FireSupportClientEndpoint.accept(message));
        context.setPacketHandled(true);
    }
}
