package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public class S2CVehicleEraState {

    public int entityId;
    public List<String> inactiveBones = List.of();

    public static S2CVehicleEraState create(AbstractVehicle vehicle, Collection<String> inactiveBones) {
        S2CVehicleEraState msg = new S2CVehicleEraState();
        msg.entityId = vehicle.getId();
        msg.inactiveBones = inactiveBones == null ? List.of() : List.copyOf(inactiveBones);
        return msg;
    }

    public static void encode(S2CVehicleEraState msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeVarInt(msg.inactiveBones.size());
        for (String boneName : msg.inactiveBones) {
            buf.writeUtf(boneName, 128);
        }
    }

    public static S2CVehicleEraState decode(FriendlyByteBuf buf) {
        S2CVehicleEraState msg = new S2CVehicleEraState();
        msg.entityId = buf.readInt();
        int size = buf.readVarInt();
        List<String> bones = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            bones.add(buf.readUtf(128));
        }
        msg.inactiveBones = bones;
        return msg;
    }

    public static void handle(S2CVehicleEraState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientVehicleEraState.apply(msg)));
    }
}
