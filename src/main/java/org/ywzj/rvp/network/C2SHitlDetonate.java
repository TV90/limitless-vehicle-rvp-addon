package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;

import java.util.function.Supplier;

/**
 * 人在回路（HITL）视角下玩家右键提前引爆导弹的请求。
 * 武器配置 {@code hitl_right_click_detonate: true} 时，客户端右键发送本包；
 * 服务端校验归属后在导弹当前位置走正常爆炸链。
 */
public class C2SHitlDetonate {

    public int missileEntityId;

    public static C2SHitlDetonate of(int missileEntityId) {
        C2SHitlDetonate msg = new C2SHitlDetonate();
        msg.missileEntityId = missileEntityId;
        return msg;
    }

    public static void encode(C2SHitlDetonate msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
    }

    public static C2SHitlDetonate decode(FriendlyByteBuf buf) {
        C2SHitlDetonate msg = new C2SHitlDetonate();
        msg.missileEntityId = buf.readInt();
        return msg;
    }

    public static void handle(C2SHitlDetonate msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.level() == null) {
                return;
            }
            Entity e = player.level().getEntity(msg.missileEntityId);
            if (!(e instanceof RVP_MissileEntity missile)) {
                return;
            }
            if (missile.getOwner() != player) {
                return;
            }
            missile.rvp$hitlDetonate();
        });
    }
}
