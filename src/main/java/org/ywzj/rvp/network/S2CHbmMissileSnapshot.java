package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientHbmMissileState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CHbmMissileSnapshot {

    public ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    public List<Entry> entries = List.of();

    public enum Affiliation {
        OWN,
        FRIEND,
        HOSTILE
    }

    public record Entry(
            int entityId,
            Affiliation affiliation,
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz,
            float yaw,
            @Nullable Vec3 targetPos
    ) {}

    public static void encode(S2CHbmMissileSnapshot msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeInt(entry.entityId());
            buf.writeEnum(entry.affiliation());
            buf.writeDouble(entry.x());
            buf.writeDouble(entry.y());
            buf.writeDouble(entry.z());
            buf.writeDouble(entry.vx());
            buf.writeDouble(entry.vy());
            buf.writeDouble(entry.vz());
            buf.writeFloat(entry.yaw());
            buf.writeBoolean(entry.targetPos() != null);
            if (entry.targetPos() != null) {
                buf.writeDouble(entry.targetPos().x);
                buf.writeDouble(entry.targetPos().y);
                buf.writeDouble(entry.targetPos().z);
            }
        }
    }

    public static S2CHbmMissileSnapshot decode(FriendlyByteBuf buf) {
        S2CHbmMissileSnapshot msg = new S2CHbmMissileSnapshot();
        msg.dimension = buf.readResourceLocation();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int entityId = buf.readInt();
            Affiliation affiliation = buf.readEnum(Affiliation.class);
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            double vx = buf.readDouble();
            double vy = buf.readDouble();
            double vz = buf.readDouble();
            float yaw = buf.readFloat();
            Vec3 targetPos = null;
            if (buf.readBoolean()) {
                targetPos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            }
            entries.add(new Entry(entityId, affiliation, x, y, z, vx, vy, vz, yaw, targetPos));
        }
        msg.entries = entries;
        return msg;
    }

    public static void handle(S2CHbmMissileSnapshot msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientHbmMissileState.applySnapshot(msg)));
    }
}
