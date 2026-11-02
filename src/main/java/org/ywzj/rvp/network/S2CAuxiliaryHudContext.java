package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.uav.RVP_AuxiliaryHudSnapshot;
import java.util.function.Supplier;

/** 服务端仅向当前乘员发送实例身份、冷却和操作许可。 */
public record S2CAuxiliaryHudContext(RVP_AuxiliaryHudSnapshot snapshot) {
    public static void encode(S2CAuxiliaryHudContext message, FriendlyByteBuf buffer) {
        var s = message.snapshot;
        buffer.writeUUID(s.vehicleUuid()); buffer.writeEnum(s.role()); buffer.writeVarInt(s.cooldownTicks());
        buffer.writeBoolean(s.deployed()); buffer.writeBoolean(s.deployAllowed()); buffer.writeBoolean(s.switchAllowed());
    }
    public static S2CAuxiliaryHudContext decode(FriendlyByteBuf buffer) {
        return new S2CAuxiliaryHudContext(new RVP_AuxiliaryHudSnapshot(buffer.readUUID(),
                buffer.readEnum(RVP_AuxiliaryHudSnapshot.Role.class), Math.max(0, buffer.readVarInt()),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean()));
    }
    public static void handle(S2CAuxiliaryHudContext message, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get(); context.setPacketHandled(true);
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientAuxiliaryHudState.apply(message.snapshot)));
    }
}
