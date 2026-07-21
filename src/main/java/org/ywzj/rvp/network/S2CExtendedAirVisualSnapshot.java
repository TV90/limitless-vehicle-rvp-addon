package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientExtendedAirVisualState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public record S2CExtendedAirVisualSnapshot(ResourceLocation dimension, Set<Integer> entityIds,
                                           Set<Integer> motorBurningEntityIds) {

    public static void encode(S2CExtendedAirVisualSnapshot msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.entityIds.size());
        for (Integer entityId : msg.entityIds) {
            buf.writeVarInt(entityId);
        }
        buf.writeVarInt(msg.motorBurningEntityIds.size());
        for (Integer entityId : msg.motorBurningEntityIds) {
            buf.writeVarInt(entityId);
        }
    }

    public static S2CExtendedAirVisualSnapshot decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int size = buf.readVarInt();
        Set<Integer> entityIds = new HashSet<>();
        for (int i = 0; i < size; i++) {
            entityIds.add(buf.readVarInt());
        }
        int burningSize = buf.readVarInt();
        Set<Integer> motorBurningEntityIds = new HashSet<>();
        for (int i = 0; i < burningSize; i++) {
            motorBurningEntityIds.add(buf.readVarInt());
        }
        return new S2CExtendedAirVisualSnapshot(dimension, entityIds, motorBurningEntityIds);
    }

    public static void handle(S2CExtendedAirVisualSnapshot msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        context.enqueueWork(() -> RVP_ClientExtendedAirVisualState.replace(
                msg.dimension, msg.entityIds, msg.motorBurningEntityIds));
        context.setPacketHandled(true);
    }
}
