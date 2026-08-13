package org.ywzj.rvp.countermeasure.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.countermeasure.server.RVP_CountermeasureRuntimeManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * 客户端按下干扰物发射键 → 服务端触发一次齐射（flare / chaff 分键）。
 */
public class C2SFireCountermeasure {

    public int vehicleId;
    public int typeOrdinal;

    public C2SFireCountermeasure() {
    }

    public C2SFireCountermeasure(int vehicleId, RVP_EnumCountermeasureType type) {
        this.vehicleId = vehicleId;
        this.typeOrdinal = type.ordinal();
    }

    public static void encode(C2SFireCountermeasure msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
        buf.writeInt(msg.typeOrdinal);
    }

    public static C2SFireCountermeasure decode(FriendlyByteBuf buf) {
        C2SFireCountermeasure msg = new C2SFireCountermeasure();
        msg.vehicleId = buf.readInt();
        msg.typeOrdinal = buf.readInt();
        return msg;
    }

    public static void handle(C2SFireCountermeasure msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            RVP_EnumCountermeasureType type = RVP_EnumCountermeasureType.values()[
                    Math.max(0, Math.min(msg.typeOrdinal, RVP_EnumCountermeasureType.values().length - 1))];
            Entity entity = player.level().getEntity(msg.vehicleId);
            if (entity instanceof AbstractVehicle vehicle) {
                // 调用服务端干扰物运行时，推进对应类型的发射状态机并发射
                RVP_CountermeasureRuntimeManager.onFire(player, vehicle, type);
            }
        });
    }
}
