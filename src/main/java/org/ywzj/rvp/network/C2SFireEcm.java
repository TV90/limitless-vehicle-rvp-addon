package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.ecm.RVP_EcmActiveManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * 客户端按下主动ECM键 → 服务端触发持续干扰状态。
 */
public class C2SFireEcm {

    /** 载具实体 id（触发者所在载具）。 */
    public int vehicleId;

    public C2SFireEcm() {
    }

    public C2SFireEcm(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public static void encode(C2SFireEcm msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
    }

    public static C2SFireEcm decode(FriendlyByteBuf buf) {
        C2SFireEcm msg = new C2SFireEcm();
        msg.vehicleId = buf.readInt();
        return msg;
    }

    public static void handle(C2SFireEcm msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level().getEntity(msg.vehicleId);
            if (entity instanceof AbstractVehicle vehicle) {
                // 校验玩家/载具/骨块存活/冷却在管理器内完成
                RVP_EcmActiveManager.onFire(player, vehicle);
            }
        });
    }
}
