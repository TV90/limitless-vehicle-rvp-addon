package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 生物头顶伤害飘字：服务端权威结算出「实际掉血」后单发推送，客户端纯视觉渲染。
 *
 * <p>不走实体绑定（伤害来源弹体可能已 discard、目标可能已死亡），只带一个世界坐标，
 * 客户端投影成屏幕点后上飘淡出。同一 tick 内直击 + 爆炸的多段伤害已在服务端合并成
 * 一个总数（{@code RVP_DamageNumberHandler}），所以这里一条消息就是一次命中。</p>
 */
public class S2CDamageNumber {

    public double x;
    public double y;
    public double z;
    public float amount;

    public static S2CDamageNumber of(double x, double y, double z, float amount) {
        S2CDamageNumber msg = new S2CDamageNumber();
        msg.x = x;
        msg.y = y;
        msg.z = z;
        msg.amount = amount;
        return msg;
    }

    public static void encode(S2CDamageNumber msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeFloat(msg.amount);
    }

    public static S2CDamageNumber decode(FriendlyByteBuf buf) {
        S2CDamageNumber msg = new S2CDamageNumber();
        msg.x = buf.readDouble();
        msg.y = buf.readDouble();
        msg.z = buf.readDouble();
        msg.amount = buf.readFloat();
        return msg;
    }

    public static void handle(S2CDamageNumber msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_DamageNumberState.add(msg.x, msg.y, msg.z, msg.amount)));
    }
}
