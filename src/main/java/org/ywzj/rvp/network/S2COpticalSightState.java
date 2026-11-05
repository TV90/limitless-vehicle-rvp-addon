package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMotion;

import java.util.UUID;
import java.util.function.Supplier;

/** 权威实际轨迹；固定长度编码，客户端不能借快照扩大数组或加载额外实体。 */
public record S2COpticalSightState(
        /** 权威状态所属维度。 */ net.minecraft.resources.ResourceLocation dimension,
        /** 载具实体 ID。 */ int vehicleId,
        /** 载具实体身份。 */ UUID vehicleUUID,
        /** 武器站索引。 */ int stationIndex,
        /** 服务端状态所属刻。 */ long tick,
        /** 服务端确认的操作会话。 */ @Nullable UUID session,
        /** 最后执行的输入序号。 */ long sequence,
        /** 服务端采用的加减速时间，秒。 */ double accelerationSeconds,
        /** 本刻实际轨迹。 */ RVP_OpticalSightMotion.Snapshot state,
        /** 刻首基座。 */ Quaterniond baseBefore,
        /** 刻末基座。 */ Quaterniond baseAfter,
        /** 是否稳定画面横轴。 */ boolean stabilized,
        /** 当前权威请求方向，用于远端接续运动。 */ net.minecraft.world.phys.Vec3 direction,
        /** 当前真实点请求，方向锁为空。 */ @Nullable net.minecraft.world.phys.Vec3 point) {
    public static void encode(S2COpticalSightState m, FriendlyByteBuf b) {
        b.writeResourceLocation(m.dimension);
        b.writeInt(m.vehicleId); b.writeUUID(m.vehicleUUID); b.writeVarInt(m.stationIndex); b.writeLong(m.tick);
        b.writeBoolean(m.session != null); if (m.session != null) b.writeUUID(m.session);
        b.writeLong(m.sequence); b.writeFloat((float) m.accelerationSeconds);
        b.writeFloat((float) m.state.pitchVelocity()); b.writeFloat((float) m.state.yawVelocity());
        b.writeByte(m.state.phase().ordinal()); b.writeBoolean(m.stabilized);
        // 调用固定大小的基座编码；画面轨迹由基座和机械轨迹重建，只传一帧初始横轴。
        writeRotation(b, m.baseBefore); writeRotation(b, m.baseAfter);
        double[] views = m.state.views();
        writeRotation(b, new Quaterniond(views[0], views[1], views[2], views[3]));
        for (int i = 0; i <= RVP_OpticalSightMotion.STEPS; i++) {
            b.writeFloat((float) m.state.pitches()[i]); b.writeFloat((float) m.state.yaws()[i]);
        }
        b.writeDouble(m.direction.x); b.writeDouble(m.direction.y); b.writeDouble(m.direction.z);
        b.writeBoolean(m.point != null);
        if (m.point != null) { b.writeDouble(m.point.x); b.writeDouble(m.point.y); b.writeDouble(m.point.z); }
    }
    public static S2COpticalSightState decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation();
        int id = b.readInt(); UUID uuid = b.readUUID(); int index = b.readVarInt(); long tick = b.readLong();
        UUID session = b.readBoolean() ? b.readUUID() : null;
        long seq = b.readLong(); double seconds = b.readFloat(), vp = b.readFloat(), vy = b.readFloat();
        int phase = b.readUnsignedByte(); boolean stabilized = b.readBoolean();
        Quaterniond before = readRotation(b), after = readRotation(b), view = readRotation(b);
        int size = RVP_OpticalSightMotion.STEPS + 1;
        double[] pitches = new double[size], yaws = new double[size], views = new double[size * 4];
        for (int i = 0; i < size; i++) {
            pitches[i] = b.readFloat(); yaws[i] = b.readFloat();
            if (!Double.isFinite(pitches[i]) || !Double.isFinite(yaws[i])) throw new IllegalArgumentException("吊舱轨迹含非法角度");
            if (i > 0) {
                Quaterniond actual = new Quaterniond(before).slerp(after, (double) i / RVP_OpticalSightMotion.STEPS).normalize()
                        .rotateY(Math.toRadians(-yaws[i])).rotateX(Math.toRadians(pitches[i]));
                // 调用与服务端一致的横轴随动，恢复固定大小的完整姿态路径。
                view = stabilized ? RVP_OpticalSightMath.followDirection(view, RVP_OpticalSightMath.direction(actual)) : actual;
            }
            views[i * 4] = view.x; views[i * 4 + 1] = view.y; views[i * 4 + 2] = view.z; views[i * 4 + 3] = view.w;
        }
        if (phase >= RVP_OpticalSightMotion.Phase.values().length || !Double.isFinite(vp) || !Double.isFinite(vy)
                || !Double.isFinite(seconds) || seconds < 0.01 || seconds > 2) throw new IllegalArgumentException("吊舱状态参数非法");
        var direction = new net.minecraft.world.phys.Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        var point = b.readBoolean() ? new net.minecraft.world.phys.Vec3(b.readDouble(), b.readDouble(), b.readDouble()) : null;
        if (!Double.isFinite(direction.lengthSqr()) || Math.abs(direction.lengthSqr() - 1) > 1.0E-4
                || point != null && !Double.isFinite(point.lengthSqr())) throw new IllegalArgumentException("吊舱请求状态非法");
        return new S2COpticalSightState(dimension, id, uuid, index, tick, session, seq, seconds,
                new RVP_OpticalSightMotion.Snapshot(vp, vy, RVP_OpticalSightMotion.Phase.values()[phase], pitches, yaws, views),
                before, after, stabilized, direction, point);
    }
    public static void handle(S2COpticalSightState m, Supplier<NetworkEvent.Context> supplier) {
        // 调用双端安全桥；专用服务端不引用任何客户端类型。
        org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptOpticalSightState(m);
        supplier.get().setPacketHandled(true);
    }
    private static void writeRotation(FriendlyByteBuf b, Quaterniond q) {
        b.writeFloat((float) q.x); b.writeFloat((float) q.y); b.writeFloat((float) q.z); b.writeFloat((float) q.w);
    }
    private static Quaterniond readRotation(FriendlyByteBuf b) {
        Quaterniond q = new Quaterniond(b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
        double length = q.lengthSquared();
        if (!Double.isFinite(length) || length < 0.5 || length > 1.5) throw new IllegalArgumentException("吊舱姿态非法");
        return q.normalize();
    }
}
