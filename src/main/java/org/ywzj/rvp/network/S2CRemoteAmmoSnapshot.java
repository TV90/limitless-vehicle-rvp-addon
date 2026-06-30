package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientRemoteAmmoState;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CRemoteAmmoSnapshot {

    public ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    public List<Entry> entries = List.of();

    public enum Affiliation {
        OWN,
        FRIEND,
        HOSTILE
    }

    public record Entry(
            int entityId,
            @Nullable ResourceLocation weaponId,
            String displayName,
            RVP_EnumWeaponKind weaponKind,
            Affiliation affiliation,
            boolean gpsCapable,
            double speedKmh,
            double x,
            double y,
            double z,
            float yaw,
            @Nullable Vec3 guidancePos
    ) {}

    public static void encode(S2CRemoteAmmoSnapshot msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeInt(entry.entityId());
            buf.writeBoolean(entry.weaponId() != null);
            if (entry.weaponId() != null) {
                buf.writeResourceLocation(entry.weaponId());
            }
            buf.writeUtf(entry.displayName());
            buf.writeEnum(entry.weaponKind());
            buf.writeEnum(entry.affiliation());
            buf.writeBoolean(entry.gpsCapable());
            buf.writeDouble(entry.speedKmh());
            buf.writeDouble(entry.x());
            buf.writeDouble(entry.y());
            buf.writeDouble(entry.z());
            buf.writeFloat(entry.yaw());
            buf.writeBoolean(entry.guidancePos() != null);
            if (entry.guidancePos() != null) {
                buf.writeDouble(entry.guidancePos().x);
                buf.writeDouble(entry.guidancePos().y);
                buf.writeDouble(entry.guidancePos().z);
            }
        }
    }

    public static S2CRemoteAmmoSnapshot decode(FriendlyByteBuf buf) {
        S2CRemoteAmmoSnapshot msg = new S2CRemoteAmmoSnapshot();
        msg.dimension = buf.readResourceLocation();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int entityId = buf.readInt();
            ResourceLocation weaponId = buf.readBoolean() ? buf.readResourceLocation() : null;
            String displayName = buf.readUtf();
            RVP_EnumWeaponKind weaponKind = buf.readEnum(RVP_EnumWeaponKind.class);
            Affiliation affiliation = buf.readEnum(Affiliation.class);
            boolean gpsCapable = buf.readBoolean();
            double speedKmh = buf.readDouble();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yaw = buf.readFloat();
            Vec3 guidancePos = null;
            if (buf.readBoolean()) {
                guidancePos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            }
            entries.add(new Entry(entityId, weaponId, displayName, weaponKind, affiliation, gpsCapable, speedKmh, x, y, z, yaw, guidancePos));
        }
        msg.entries = entries;
        return msg;
    }

    public static void handle(S2CRemoteAmmoSnapshot msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientRemoteAmmoState.applySnapshot(msg)));
    }
}
