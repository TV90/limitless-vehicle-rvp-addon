package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;

import java.util.function.Supplier;

/** Client → server: SACLOS laser designator on/off + live aim point (MCH PacketLaserGuidanceTargeting). */
public class C2SSaclosDesignation {

    /** 操作手当前是否保持SACLOS照射会话。 */
    public boolean targeting;

    /** 操作手实时世界瞄准点X坐标，单位为格。 */
    public double x;

    /** 操作手实时世界瞄准点Y坐标，单位为格。 */
    public double y;

    /** 操作手实时世界瞄准点Z坐标，单位为格。 */
    public double z;

    /** 客户端是否请求在STABLE模式下启用服务端校验后的SACLOS PIP辅助。 */
    public boolean stablePIPAssist;

    public static C2SSaclosDesignation of(boolean targeting, Vec3 point, boolean stablePIPAssist) {
        C2SSaclosDesignation msg = new C2SSaclosDesignation();
        msg.targeting = targeting;
        msg.stablePIPAssist = targeting && stablePIPAssist;
        if (point != null) {
            msg.x = point.x;
            msg.y = point.y;
            msg.z = point.z;
        }
        return msg;
    }

    public static void encode(C2SSaclosDesignation msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.targeting);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeBoolean(msg.stablePIPAssist);
    }

    public static C2SSaclosDesignation decode(FriendlyByteBuf buf) {
        C2SSaclosDesignation msg = new C2SSaclosDesignation();
        msg.targeting = buf.readBoolean();
        msg.x = buf.readDouble();
        msg.y = buf.readDouble();
        msg.z = buf.readDouble();
        msg.stablePIPAssist = buf.readBoolean();
        return msg;
    }

    public static void handle(C2SSaclosDesignation msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Vec3 point = msg.targeting ? new Vec3(msg.x, msg.y, msg.z) : null;
            // 调用本项目SACLOS操作手会话，同时同步世界瞄准点与客户端三态请求；
            // 服务端制导源仍会重新校验武器站资格和真实雷达硬锁，不信任客户端目标。
            RVP_SaclosOperatorSession.setDesignation(
                    player.getUUID(), msg.targeting, point, msg.stablePIPAssist);
        });
    }
}
