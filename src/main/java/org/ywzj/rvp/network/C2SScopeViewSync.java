package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.sight.RVP_ScopeViewStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.function.Supplier;

/**
 * 客户端观瞄视角（SCOPE）状态上行：进入/退出观瞄或换操作站时发送，观瞄中每 30t 心跳续期。
 *
 * <p>服务端 {@link RVP_ScopeViewStateTable} 据此在出弹时把观瞄视角下的实际出弹点覆盖为
 * 观瞄相机坐标（站级配置 {@code rvp_sight_fire_disguise}）。不随每发弹上行：
 * 改装弹种代理上传（{@code VehicleMultiWeapons.doClientShoot} 不经 RVP 子武器方法）与
 * 点射后续弹都由服务端出弹器统一消费本状态，天然全弹种兼容。</p>
 */
public class C2SScopeViewSync {

    public int vehicleId;
    /** 观瞄中的操作根武器站在 vehicle.getPartUnits() 里的序号；inScope=false 时无意义。 */
    public int partUnitIndex;
    public boolean inScope;

    public C2SScopeViewSync() {
    }

    public C2SScopeViewSync(int vehicleId, int partUnitIndex, boolean inScope) {
        this.vehicleId = vehicleId;
        this.partUnitIndex = partUnitIndex;
        this.inScope = inScope;
    }

    public static void encode(C2SScopeViewSync msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
        buf.writeVarInt(msg.partUnitIndex);
        buf.writeBoolean(msg.inScope);
    }

    public static C2SScopeViewSync decode(FriendlyByteBuf buf) {
        C2SScopeViewSync msg = new C2SScopeViewSync();
        msg.vehicleId = buf.readInt();
        msg.partUnitIndex = buf.readVarInt();
        msg.inScope = buf.readBoolean();
        return msg;
    }

    public static void handle(C2SScopeViewSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!msg.inScope) {
                RVP_ScopeViewStateTable.update(player, msg.vehicleId, msg.partUnitIndex, false, 0L);
                return;
            }
            // 伪造包加固：发送者必须正乘坐该载具，观瞄状态才可信
            Entity entity = player.level().getEntity(msg.vehicleId);
            if (!(entity instanceof AbstractVehicle vehicle) || player.getVehicle() != vehicle) {
                return;
            }
            // 站序号防御：越界直接忽略（出弹侧还会做根站匹配二次校验）
            if (msg.partUnitIndex < 0 || msg.partUnitIndex >= vehicle.getPartUnits().size()) {
                return;
            }
            RVP_ScopeViewStateTable.update(player, msg.vehicleId, msg.partUnitIndex, true,
                    vehicle.level().getGameTime());
        });
    }
}
