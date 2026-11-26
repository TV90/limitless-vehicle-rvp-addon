package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.guidance.RVP_OpticalConfidenceFormula;
import org.ywzj.rvp.guidance.RVP_OpticalConfidenceRuntime;

import java.util.function.Supplier;

/** 服务端向登录或正在游戏的客户端同步已选中的数学表达式。 */
public record S2COpticalConfidenceFormula(String expression) {
    public static void encode(S2COpticalConfidenceFormula message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.expression(), RVP_OpticalConfidenceFormula.MAX_LENGTH);
    }

    public static S2COpticalConfidenceFormula decode(FriendlyByteBuf buffer) {
        return new S2COpticalConfidenceFormula(buffer.readUtf(RVP_OpticalConfidenceFormula.MAX_LENGTH));
    }

    public static void handle(S2COpticalConfidenceFormula message, Supplier<NetworkEvent.Context> supplier) {
        // 登记客户端快照，识别 HUD 在下一帧使用新的公式。
        RVP_OpticalConfidenceRuntime.setClient(RVP_OpticalConfidenceFormula.compile(message.expression()));
        supplier.get().setPacketHandled(true);
    }
}
