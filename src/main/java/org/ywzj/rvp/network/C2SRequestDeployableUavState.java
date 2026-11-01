package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_DeployableUavCatalog;
import org.ywzj.rvp.uav.RVP_DeployableUavStateSavedData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.UUID;
import java.util.function.Supplier;

/** 客户端请求指定母车状态；请求不携带策略字段，服务端回送权威快照。 */
public record C2SRequestDeployableUavState(int parentEntityId, UUID parentUuid) {
    /** 编码父车 entity id 与 UUID，服务端必须同时匹配。 */
    public static void encode(C2SRequestDeployableUavState msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.parentEntityId()); buf.writeUUID(msg.parentUuid());
    }
    /** 解码父车身份请求。 */
    public static C2SRequestDeployableUavState decode(FriendlyByteBuf buf) {
        return new C2SRequestDeployableUavState(buf.readVarInt(), buf.readUUID());
    }
    /** 校验实体身份和访问距离后，同步冷却、选择及旧子机租约状态。 */
    public static void handle(C2SRequestDeployableUavState msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)
                    || !(level.getEntity(msg.parentEntityId()) instanceof AbstractVehicle parent)
                    || !parent.getUUID().equals(msg.parentUuid())) return;
            String selected = RVP_DeployableUavCatalog.getSelectedOptionId(level, parent.getUUID(), parent.getVehicleId());
            var state = RVP_DeployableUavStateSavedData.snapshot(level, parent.getUUID(), selected);
            boolean legacyLinked = org.ywzj.rvp.uav.RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent) != null;
            boolean tooFar = player.distanceToSqr(parent) > 256.0;
            String reason = tooFar ? "too_far" : state.lockedReason();
            if (reason.isEmpty() && legacyLinked) reason = "deployed";
            RVP_Network.CHANNEL.sendTo(new S2CDeployableUavState(parent.getUUID(), selected,
                    state.revision(), state.remainingCooldownTick(), state.deployed() || legacyLinked, reason),
                    player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        });
    }
}