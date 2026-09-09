package org.ywzj.rvp.network.firesupport;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportEndReason;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionManager;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionState;

import java.util.UUID;
import java.util.function.Supplier;

/** 仅在阶段、进度、停火或终态变化时发送的任务状态。 */
public record S2CFireSupportMissionUpdate(
        /** 任务 UUID。 */ UUID missionId,
        /** 任务绑定终端实例 UUID，仅发给任务所有者。 */ UUID terminalInstanceId,
        /** 当前权威阶段。 */ RVP_FireSupportMissionState state,
        /** 终态或异常原因。 */ RVP_FireSupportEndReason reason,
        /** 已成功加入世界的顶层弹体数。 */ int deliveredRounds,
        /** 冻结计划总弹数。 */ int totalRounds,
        /** 呼叫阶段权威截止 Tick。 */ long callDeadlineTick,
        /** 下一发权威计划 Tick。 */ long nextRoundTick,
        /** 停火生效 Tick；Long.MAX_VALUE 表示没有待生效停火。 */ long ceaseFireEffectiveTick,
        /** 支援机是否正在等待路径或实体恢复。 */ boolean aircraftRecovering,
        /** 支援机恢复窗口截止 Tick；未恢复时为 Long.MAX_VALUE。 */ long aircraftRecoveryDeadlineTick) {
    /** 从服务端只读任务摘要构造消息。 */
    public static S2CFireSupportMissionUpdate from(RVP_FireSupportMissionManager.MissionView view) {
        return new S2CFireSupportMissionUpdate(view.missionId(), view.terminalInstanceId(), view.state(), view.reason(),
                view.deliveredRounds(), view.totalRounds(), view.callDeadlineTick(), view.nextRoundTick(),
                view.ceaseFireEffectiveTick(), view.aircraftRecovering(), view.aircraftRecoveryDeadlineTick());
    }

    /** 编码固定大小任务状态。 */
    public static void encode(S2CFireSupportMissionUpdate message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.missionId);
        buffer.writeUUID(message.terminalInstanceId);
        buffer.writeEnum(message.state);
        buffer.writeEnum(message.reason);
        buffer.writeVarInt(message.deliveredRounds);
        buffer.writeVarInt(message.totalRounds);
        buffer.writeLong(message.callDeadlineTick);
        buffer.writeLong(message.nextRoundTick);
        buffer.writeLong(message.ceaseFireEffectiveTick);
        buffer.writeBoolean(message.aircraftRecovering);
        buffer.writeLong(message.aircraftRecoveryDeadlineTick);
    }

    /** 解码固定大小任务状态并校验计数关系。 */
    public static S2CFireSupportMissionUpdate decode(FriendlyByteBuf buffer) {
        S2CFireSupportMissionUpdate message = new S2CFireSupportMissionUpdate(buffer.readUUID(), buffer.readUUID(),
                buffer.readEnum(RVP_FireSupportMissionState.class), buffer.readEnum(RVP_FireSupportEndReason.class),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readLong(), buffer.readLong(), buffer.readLong(),
                buffer.readBoolean(), buffer.readLong());
        if (message.deliveredRounds < 0 || message.totalRounds < message.deliveredRounds || message.totalRounds > 512) {
            throw new io.netty.handler.codec.DecoderException("非法炮火任务弹数");
        }
        if (message.aircraftRecovering == (message.aircraftRecoveryDeadlineTick == Long.MAX_VALUE)) {
            throw new io.netty.handler.codec.DecoderException("非法空中支援恢复状态");
        }
        return message;
    }

    /** 经公共端口交给客户端状态。 */
    public static void handle(S2CFireSupportMissionUpdate message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> RVP_FireSupportClientEndpoint.accept(message));
        context.setPacketHandled(true);
    }
}
