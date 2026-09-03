package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.wingsweep.RVP_WingSweepState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;

import java.util.function.Supplier;

/**
 * [RVP] 可变后掠翼手动切换请求（C2S）。
 *
 * <p>客户端 ↑/↓（复用本体 FUNCTIONAL 键，边沿检测）仅发意图，服务端做四重校验后
 * 交 {@link RVP_WingSweepState#handleToggle} 执行：
 * 发送者非空 → 载具存在且为固定翼 → 无推力矢量部件（VTOL 的矢量控制让给本体）→
 * 发送者是当前驾驶员。状态维护与同步全部在服务端（{@code RVP_WingSweepState}）。</p>
 */
public class C2SWingSweepToggle {

    private final int vehicleId;
    /** true=↑（进入手动/切换形态），false=↓（恢复自动）。 */
    private final boolean up;

    public C2SWingSweepToggle(int vehicleId, boolean up) {
        this.vehicleId = vehicleId;
        this.up = up;
    }

    public static void encode(C2SWingSweepToggle msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
        buf.writeBoolean(msg.up);
    }

    public static C2SWingSweepToggle decode(FriendlyByteBuf buf) {
        return new C2SWingSweepToggle(buf.readInt(), buf.readBoolean());
    }

    public static void handle(C2SWingSweepToggle msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !(player.level().getEntity(msg.vehicleId) instanceof AbstractVehicle vehicle)) {
                return;
            }
            // 校验 1：仅当前驾驶员可切换（乘员/炮手/路人拒绝）
            if (vehicle.getDriver() != player) {
                return;
            }
            // 校验 2：仅无矢量部件的固定翼（与客户端守卫同口径，防伪造包）
            if (!(vehicle instanceof FixedWingVehicle fixedWing) || fixedWing.thrustUnit != null) {
                return;
            }
            // 校验 3：隐藏部件必须存在（协议约定的能力信号）
            if (vehicle.getPartUnit(RVP_WingSweepState.PART_MANUAL_ID).isEmpty()
                    || vehicle.getPartUnit(RVP_WingSweepState.PART_FORM_ID).isEmpty()) {
                return;
            }
            RVP_WingSweepState.handleToggle(fixedWing, msg.up);
        });
    }
}
