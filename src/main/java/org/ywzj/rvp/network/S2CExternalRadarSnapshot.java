package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientExternalRadarState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class S2CExternalRadarSnapshot {
    public ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    @Nullable
    public UUID launcherVehicleUuid;
    @Nullable
    public UUID relayVehicleUuid;
    public boolean relayRadarOn;
    public int requestedEntityId = Integer.MIN_VALUE;
    public int lockedEntityId = Integer.MIN_VALUE;
    public List<Entry> entries = List.of();
    public List<RadarSector> sectors = List.of();

    public enum Affiliation {
        OWN,
        FRIEND,
        HOSTILE,
        UNKNOWN
    }

    public record Entry(
            int entityId,
            String nctrLabel,
            Affiliation affiliation,
            boolean ammo,
            double x,
            double y,
            double z,
            double velocityX,
            double velocityY,
            double velocityZ
    ) {
        public Vec3 position() {
            return new Vec3(x, y, z);
        }

        public Vec3 velocity() {
            return new Vec3(velocityX, velocityY, velocityZ);
        }
    }

    public record RadarSector(
            String radarId,
            double x,
            double y,
            double z,
            float yaw,
            float yRotMin,
            float yRotMax,
            double maxDistance
    ) {
        public Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    public static void encode(S2CExternalRadarSnapshot msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeBoolean(msg.launcherVehicleUuid != null);
        if (msg.launcherVehicleUuid != null) {
            buf.writeUUID(msg.launcherVehicleUuid);
        }
        buf.writeBoolean(msg.relayVehicleUuid != null);
        if (msg.relayVehicleUuid != null) {
            buf.writeUUID(msg.relayVehicleUuid);
        }
        buf.writeBoolean(msg.relayRadarOn);
        buf.writeInt(msg.requestedEntityId);
        buf.writeInt(msg.lockedEntityId);
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeInt(entry.entityId());
            buf.writeUtf(entry.nctrLabel());
            buf.writeEnum(entry.affiliation());
            buf.writeBoolean(entry.ammo());
            buf.writeDouble(entry.x());
            buf.writeDouble(entry.y());
            buf.writeDouble(entry.z());
            buf.writeDouble(entry.velocityX());
            buf.writeDouble(entry.velocityY());
            buf.writeDouble(entry.velocityZ());
        }
        buf.writeVarInt(msg.sectors.size());
        for (RadarSector sector : msg.sectors) {
            buf.writeUtf(sector.radarId());
            buf.writeDouble(sector.x());
            buf.writeDouble(sector.y());
            buf.writeDouble(sector.z());
            buf.writeFloat(sector.yaw());
            buf.writeFloat(sector.yRotMin());
            buf.writeFloat(sector.yRotMax());
            buf.writeDouble(sector.maxDistance());
        }
    }

    public static S2CExternalRadarSnapshot decode(FriendlyByteBuf buf) {
        S2CExternalRadarSnapshot msg = new S2CExternalRadarSnapshot();
        msg.dimension = buf.readResourceLocation();
        msg.launcherVehicleUuid = buf.readBoolean() ? buf.readUUID() : null;
        msg.relayVehicleUuid = buf.readBoolean() ? buf.readUUID() : null;
        msg.relayRadarOn = buf.readBoolean();
        msg.requestedEntityId = buf.readInt();
        msg.lockedEntityId = buf.readInt();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(
                    buf.readInt(),
                    buf.readUtf(),
                    buf.readEnum(Affiliation.class),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble()
            ));
        }
        msg.entries = entries;
        int sectorCount = buf.readVarInt();
        List<RadarSector> sectors = new ArrayList<>(sectorCount);
        for (int i = 0; i < sectorCount; i++) {
            sectors.add(new RadarSector(
                    buf.readUtf(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readDouble()
            ));
        }
        msg.sectors = sectors;
        return msg;
    }

    public static void handle(S2CExternalRadarSnapshot msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientExternalRadarState.applySnapshot(msg)));
    }
}
