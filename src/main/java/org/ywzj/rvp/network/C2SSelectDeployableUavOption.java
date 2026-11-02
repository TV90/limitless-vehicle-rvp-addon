package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_DeployableUavCatalog;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.uav.RVP_DeployableUavStateSavedData;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.UUID;
import java.util.function.Supplier;

/** 服务端校验并原子保存母车辅助载具选择。 */
public record C2SSelectDeployableUavOption(int parentEntityId, UUID parentUuid, String optionId,
                                            long expectedRevision) {
    /** 编码请求实体身份、候选 ID 与客户端观察到的 revision。 */
    public static void encode(C2SSelectDeployableUavOption msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.parentEntityId()); buf.writeUUID(msg.parentUuid());
        buf.writeUtf(msg.optionId(), 128); buf.writeLong(msg.expectedRevision());
    }
    /** 解码请求，后续所有字段仍由服务端重新验证。 */
    public static C2SSelectDeployableUavOption decode(FriendlyByteBuf buf) {
        return new C2SSelectDeployableUavOption(buf.readVarInt(), buf.readUUID(), buf.readUtf(128), buf.readLong());
    }
    /** 主线程处理请求，任何可识别父车请求都会收到当前权威快照。 */
    public static void handle(C2SSelectDeployableUavOption msg, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)
                    || !(level.getEntity(msg.parentEntityId()) instanceof AbstractVehicle parent)
                    || !parent.getUUID().equals(msg.parentUuid())) return;
            String rejection = null;
            boolean hasTool = player.getItemInHand(InteractionHand.MAIN_HAND).is(AllItems.MODDING_TOOL.get())
                    || player.getItemInHand(InteractionHand.OFF_HAND).is(AllItems.MODDING_TOOL.get());
            boolean linkedChild = RVP_DeployableUavStateSavedData.hasActiveDeployment(level, parent.getUUID())
                    || org.ywzj.rvp.uav.RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent) != null;
            if (parent.isRemoved() || !parent.isAlive() || parent.isDestroyed()) rejection = "parent_unavailable";
            else if (player.distanceToSqr(parent) > 256.0) rejection = "too_far";
            else if (!hasTool) rejection = "tool_required";
            else if (RVP_DeployableUavCatalog.getOption(parent.getVehicleId(), msg.optionId()) == null)
                rejection = "option_invalid";
            else if (CommonAssetsManager.vehicleDataManager().getVehicleData(
                    RVP_DeployableUavCatalog.getOption(parent.getVehicleId(), msg.optionId()).vehicleId()).isEmpty())
                rejection = "option_unavailable";
            else if (linkedChild) {
                String currentReason = RVP_DeployableUavStateSavedData.snapshot(level, parent.getUUID(),
                        RVP_DeployableUavCatalog.getDefaultOptionId(parent.getVehicleId())).lockedReason();
                rejection = currentReason.equals("returning") || currentReason.equals("terminating")
                        ? currentReason : "deployed";
            }
            else if (RVP_DeployableUavStateSavedData.remainingCooldown(level, parent.getUUID()) > 0)
                rejection = "cooldown";
            else if (!RVP_VehicleExtendedConfigManager.INSTANCE.canModVehicle(parent))
                rejection = "modification_locked";
            else if (!RVP_DeployableUavStateSavedData.select(level, parent.getUUID(), msg.optionId(), msg.expectedRevision()))
                rejection = "stale_revision";
            sendState(player, level, parent, rejection);
        });
    }

    /** 统一回传当前存档选择和锁状态，拒绝时不伪造成功候选。 */
    private static void sendState(ServerPlayer player, ServerLevel level, AbstractVehicle parent, String rejection) {
        String selected = RVP_DeployableUavCatalog.getSelectedOptionId(level, parent.getUUID(), parent.getVehicleId());
        var state = RVP_DeployableUavStateSavedData.snapshot(level, parent.getUUID(), selected);
        boolean legacyLinked = org.ywzj.rvp.uav.RVP_LinkedUavStateTable.getLinkedChildVehicleUuid(parent) != null;
        String reason = rejection != null ? rejection : state.lockedReason();
        if (reason.isEmpty() && legacyLinked) reason = "deployed";
        RVP_Network.CHANNEL.sendTo(new S2CDeployableUavState(parent.getUUID(), selected,
                state.revision(), state.remainingCooldownTick(), state.deployed() || legacyLinked, reason),
                player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    }
}