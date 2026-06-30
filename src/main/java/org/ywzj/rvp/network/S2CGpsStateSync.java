package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.weapon.gps.GPSTarget;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CGpsStateSync {

    public RVP_ClientGPSState.Mode mode;
    public List<RVP_ClientGPSState.Point> points = List.of();
    public int nextIndex;

    public static S2CGpsStateSync of(GPSTargetManager.Snapshot snapshot) {
        S2CGpsStateSync msg = new S2CGpsStateSync();
        msg.mode = snapshot.mode();
        msg.nextIndex = snapshot.nextIndex();
        msg.points = snapshot.points().stream()
                .map(target -> new RVP_ClientGPSState.Point(target.dimension(), target.pos()))
                .toList();
        return msg;
    }

    public static void encode(S2CGpsStateSync msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.mode);
        buf.writeInt(msg.nextIndex);
        buf.writeInt(msg.points.size());
        for (RVP_ClientGPSState.Point point : msg.points) {
            buf.writeResourceLocation(point.dimension());
            buf.writeDouble(point.pos().x);
            buf.writeDouble(point.pos().y);
            buf.writeDouble(point.pos().z);
        }
    }

    public static S2CGpsStateSync decode(FriendlyByteBuf buf) {
        S2CGpsStateSync msg = new S2CGpsStateSync();
        msg.mode = buf.readEnum(RVP_ClientGPSState.Mode.class);
        msg.nextIndex = buf.readInt();
        int count = buf.readInt();
        List<RVP_ClientGPSState.Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new RVP_ClientGPSState.Point(
                    buf.readResourceLocation(),
                    new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            ));
        }
        msg.points = points;
        return msg;
    }

    public static void handle(S2CGpsStateSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RVP_ClientGPSState.replace(msg.mode, msg.points, msg.nextIndex)));
    }
}
