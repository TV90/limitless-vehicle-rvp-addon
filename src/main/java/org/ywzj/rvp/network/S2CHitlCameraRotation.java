package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** 仅向当前操作者发送浮点姿态，绕开原版实体角度的字节量化；不改变飞行结算。 */
public record S2CHitlCameraRotation(int missileId, long tick, float yaw, float pitch) {
    /** 按固定顺序编码当前弹体与权威姿态。 */
    public static void encode(S2CHitlCameraRotation message, FriendlyByteBuf buffer) {
        buffer.writeInt(message.missileId);
        buffer.writeLong(message.tick);
        buffer.writeFloat(message.yaw);
        buffer.writeFloat(message.pitch);
    }

    public static S2CHitlCameraRotation decode(FriendlyByteBuf buffer) {
        return new S2CHitlCameraRotation(buffer.readInt(), buffer.readLong(), buffer.readFloat(), buffer.readFloat());
    }

    /** consumerMainThread 已调度到主线程；专服不加载客户端镜头类。 */
    public static void handle(S2CHitlCameraRotation message, Supplier<NetworkEvent.Context> context) {
        context.get().setPacketHandled(true);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                // 调用当前会话镜头入口，旧弹包不能影响新镜头。
                org.ywzj.rvp.client.state.RVP_ClientHitlCamera.acceptRotation(
                        message.missileId, message.tick, message.yaw, message.pitch));
    }
}
