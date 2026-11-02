package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;

import java.util.function.Supplier;

public class C2SHitlDesignate {

    public int missileEntityId;
    public int targetEntityId = -1;
    public Vec3 targetPos;
    public boolean clearDesignation;
    /** 载具弱点的稳定骨名/cube 索引；非载具和点指定为空。 */
    public org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key targetObb;

    public static C2SHitlDesignate clear(int missileEntityId) {
        C2SHitlDesignate msg = new C2SHitlDesignate();
        msg.missileEntityId = missileEntityId;
        msg.clearDesignation = true;
        return msg;
    }

    public static C2SHitlDesignate point(int missileEntityId, Vec3 targetPos) {
        C2SHitlDesignate msg = new C2SHitlDesignate();
        msg.missileEntityId = missileEntityId;
        msg.targetPos = targetPos;
        return msg;
    }

    public static C2SHitlDesignate entity(int missileEntityId, int targetEntityId, Vec3 targetPos) {
        C2SHitlDesignate msg = new C2SHitlDesignate();
        msg.missileEntityId = missileEntityId;
        msg.targetEntityId = targetEntityId;
        msg.targetPos = targetPos;
        return msg;
    }

    public static C2SHitlDesignate entity(int missileId, int targetId, Vec3 point,
            org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key key) {
        C2SHitlDesignate msg = entity(missileId, targetId, point);
        msg.targetObb = key;
        return msg;
    }

    public static void encode(C2SHitlDesignate msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missileEntityId);
        buf.writeBoolean(msg.clearDesignation);
        if (msg.clearDesignation) {
            return;
        }
        buf.writeInt(msg.targetEntityId);
        buf.writeBoolean(msg.targetPos != null);
        if (msg.targetPos != null) {
            buf.writeDouble(msg.targetPos.x);
            buf.writeDouble(msg.targetPos.y);
            buf.writeDouble(msg.targetPos.z);
        }
        buf.writeBoolean(msg.targetObb != null);
        if (msg.targetObb != null) {
            buf.writeUtf(msg.targetObb.bone(), 128);
            buf.writeVarInt(msg.targetObb.index());
        }
    }

    public static C2SHitlDesignate decode(FriendlyByteBuf buf) {
        C2SHitlDesignate msg = new C2SHitlDesignate();
        msg.missileEntityId = buf.readInt();
        msg.clearDesignation = buf.readBoolean();
        if (msg.clearDesignation) {
            return msg;
        }
        msg.targetEntityId = buf.readInt();
        if (buf.readBoolean()) {
            msg.targetPos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        if (buf.readBoolean()) msg.targetObb = new org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key(buf.readUtf(128), buf.readVarInt());
        return msg;
    }

    public static void handle(C2SHitlDesignate msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.level() == null) {
                return;
            }
            Entity e = player.level().getEntity(msg.missileEntityId);
            if (!(e instanceof RVP_MissileEntity missile)) {
                return;
            }
            if (missile.getOwner() != player) {
                return;
            }
            if (msg.clearDesignation) {
                missile.rvp$clearHitlDesignation();
                return;
            }
            if (msg.targetEntityId >= 0) {
                Entity target = player.level().getEntity(msg.targetEntityId);
                if (target != null && target.isAlive()) {
                    if (target instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle && msg.targetObb != null) {
                        // 调用服务端模型校验和同一权限门，不能把客户端任意坐标当作车体弱点。
                        missile.rvp$setHitlDesignatedEntity(target, msg.targetObb);
                        return;
                    }
                    Entity current = missile.getTargetEntity();
                    if (current != null && current.isAlive() && current.getId() == target.getId()) {
                        missile.rvp$setHitlDesignatedTarget(target.getBoundingBox().getCenter());
                        return;
                    }
                    missile.rvp$setHitlDesignatedEntity(target);
                    return;
                }
            }
            if (msg.targetPos != null) {
                missile.rvp$setHitlDesignatedTarget(msg.targetPos);
            }
        });
    }
}
