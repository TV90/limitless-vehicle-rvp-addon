package org.ywzj.rvp.maintenance.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientMaintenanceState;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：快速维修 HUD 状态同步（节流推送，结构对齐 S2CCountermeasureHudSync）。
 *
 * <p>{@code hasMaintenance} 为 false 时客户端清除该载具的状态行（HUD 递补规则自动收起）。</p>
 */
public final class S2CMaintenanceSync {

    private final int vehicleEntityId;
    private final boolean hasMaintenance;
    /** 冷却剩余 tick（0 = 就绪）。 */
    private final int cooldownRemain;
    /** 生效剩余 tick（>0 = 维修中）。 */
    private final int useRemain;
    /** 单次生效总时长 tick（客户端显示进度用）。 */
    private final int useTimeTotal;

    public S2CMaintenanceSync(int vehicleEntityId, boolean hasMaintenance,
                              int cooldownRemain, int useRemain, int useTimeTotal) {
        this.vehicleEntityId = vehicleEntityId;
        this.hasMaintenance = hasMaintenance;
        this.cooldownRemain = cooldownRemain;
        this.useRemain = useRemain;
        this.useTimeTotal = useTimeTotal;
    }

    public static void encode(S2CMaintenanceSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeBoolean(msg.hasMaintenance);
        buf.writeVarInt(msg.cooldownRemain);
        buf.writeVarInt(msg.useRemain);
        buf.writeVarInt(msg.useTimeTotal);
    }

    public static S2CMaintenanceSync decode(FriendlyByteBuf buf) {
        return new S2CMaintenanceSync(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt());
    }

    public static void handle(S2CMaintenanceSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> apply(msg));
    }

    @OnlyIn(Dist.CLIENT)
    private static void apply(S2CMaintenanceSync msg) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        if (!msg.hasMaintenance) {
            RVP_ClientMaintenanceState.clear(msg.vehicleEntityId);
        } else {
            RVP_ClientMaintenanceState.update(msg.vehicleEntityId,
                    msg.cooldownRemain, msg.useRemain, msg.useTimeTotal);
        }
    }
}
