package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CHitlLinkState {

    public int missileEntityId;
    public boolean blocked;
    public boolean severed;
    /** 是否被 DIRCM 干扰（HITL 临时干扰：白闪滤镜驱动，与雪花互斥）。 */
    public boolean dircmJam;
    /** DIRCM 干扰剩余 tick（客户端白闪消退计时）。 */
    public int dircmJamRemainTick;
    /** DIRCM 干扰总时长 tick（客户端白闪进度计算基准，TV=60 / CLOS_TV=120）。 */
    public int dircmJamTotalTick;
    /** 权威锁点、脱锁原因与独立禁锁倒计时。 */
    public org.ywzj.rvp.hitl.RVP_HitlLockSnapshot lock;

    public static S2CHitlLinkState of(int missileEntityId, boolean blocked, boolean severed,
                                      boolean dircmJam, int dircmJamRemainTick, int dircmJamTotalTick,
                                      org.ywzj.rvp.hitl.RVP_HitlLockSnapshot lock) {
        S2CHitlLinkState msg = new S2CHitlLinkState();
        msg.missileEntityId = missileEntityId;
        msg.blocked = blocked;
        msg.severed = severed;
        msg.dircmJam = dircmJam;
        msg.dircmJamRemainTick = dircmJamRemainTick;
        msg.dircmJamTotalTick = dircmJamTotalTick;
        msg.lock = lock;
        return msg;
    }

    public static void encode(S2CHitlLinkState msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
        buf.writeBoolean(msg.blocked);
        buf.writeBoolean(msg.severed);
        buf.writeBoolean(msg.dircmJam);
        buf.writeVarInt(msg.dircmJamRemainTick);
        buf.writeVarInt(msg.dircmJamTotalTick);
        buf.writeEnum(msg.lock.status());
        buf.writeInt(msg.lock.entityId());
        buf.writeVarInt(msg.lock.noRedesignateTicks());
        buf.writeBoolean(msg.lock.point() != null);
        if (msg.lock.point() != null) {
            buf.writeDouble(msg.lock.point().x);
            buf.writeDouble(msg.lock.point().y);
            buf.writeDouble(msg.lock.point().z);
        }
        buf.writeBoolean(msg.lock.obb() != null);
        if (msg.lock.obb() != null) {
            buf.writeUtf(msg.lock.obb().bone(), 128);
            buf.writeVarInt(msg.lock.obb().index());
        }
    }

    public static S2CHitlLinkState decode(FriendlyByteBuf buf) {
        S2CHitlLinkState msg = new S2CHitlLinkState();
        msg.missileEntityId = buf.readInt();
        msg.blocked = buf.readBoolean();
        msg.severed = buf.readBoolean();
        msg.dircmJam = buf.readBoolean();
        msg.dircmJamRemainTick = buf.readVarInt();
        msg.dircmJamTotalTick = buf.readVarInt();
        var status = buf.readEnum(org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.class);
        int targetId = buf.readInt();
        int noRedesignate = Math.max(0, buf.readVarInt());
        var point = buf.readBoolean() ? new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
        var obb = buf.readBoolean() ? new org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key(buf.readUtf(128), buf.readVarInt()) : null;
        msg.lock = new org.ywzj.rvp.hitl.RVP_HitlLockSnapshot(status, targetId, point, obb, noRedesignate);
        return msg;
    }

    public static void handle(S2CHitlLinkState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            org.ywzj.rvp.client.state.RVP_ClientHitlState.onHitlLinkState(
                    msg.missileEntityId, msg.blocked, msg.severed);
            org.ywzj.rvp.client.state.RVP_ClientHitlState.onHitlDircmJam(
                    msg.missileEntityId, msg.dircmJam, msg.dircmJamRemainTick, msg.dircmJamTotalTick);
            org.ywzj.rvp.client.state.RVP_ClientHitlState.onHitlLockState(msg.missileEntityId, msg.lock);
        }));
    }
}
