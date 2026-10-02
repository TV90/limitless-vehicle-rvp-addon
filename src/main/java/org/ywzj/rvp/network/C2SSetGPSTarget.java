package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.rvp.weapon.gps.RVP_GpsModeSupport;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.function.Supplier;

public class C2SSetGPSTarget {

    public enum Action {
        SET_SINGLE,
        ADD_POINT,
        CLEAR_ALL,
        SET_MODE,
        /** [RVP] FAST 模式开火键写点：覆盖单点但保持 FAST 模式（追加在枚举末尾，兼容旧序号）。 */
        FAST_SET
    }

    public Action action;
    public RVP_ClientGPSState.Mode mode;
    public ResourceLocation dimension;
    public double x;
    public double y;
    public double z;

    public static C2SSetGPSTarget set(ResourceLocation dimension, Vec3 pos) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.SET_SINGLE;
        msg.dimension = dimension;
        msg.x = pos.x;
        msg.y = pos.y;
        msg.z = pos.z;
        msg.mode = RVP_ClientGPSState.Mode.SINGLE;
        return msg;
    }

    public static C2SSetGPSTarget add(ResourceLocation dimension, Vec3 pos) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.ADD_POINT;
        msg.dimension = dimension;
        msg.x = pos.x;
        msg.y = pos.y;
        msg.z = pos.z;
        msg.mode = RVP_ClientGPSState.Mode.MULTI;
        return msg;
    }

    public static C2SSetGPSTarget clear() {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.CLEAR_ALL;
        msg.dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        msg.mode = RVP_ClientGPSState.Mode.SINGLE;
        return msg;
    }

    public static C2SSetGPSTarget setMode(RVP_ClientGPSState.Mode mode) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.SET_MODE;
        msg.mode = mode == null ? RVP_ClientGPSState.Mode.SINGLE : mode;
        msg.dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        return msg;
    }

    /** [RVP] FAST 模式开火键写点：覆盖装订点且服务端保持 FAST 模式。 */
    public static C2SSetGPSTarget fastSet(ResourceLocation dimension, Vec3 pos) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = Action.FAST_SET;
        msg.dimension = dimension;
        msg.x = pos.x;
        msg.y = pos.y;
        msg.z = pos.z;
        msg.mode = RVP_ClientGPSState.Mode.FAST;
        return msg;
    }

    public static void encode(C2SSetGPSTarget msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeEnum(msg.mode);
        buf.writeResourceLocation(msg.dimension);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
    }

    public static C2SSetGPSTarget decode(FriendlyByteBuf buf) {
        C2SSetGPSTarget msg = new C2SSetGPSTarget();
        msg.action = buf.readEnum(Action.class);
        msg.mode = buf.readEnum(RVP_ClientGPSState.Mode.class);
        msg.dimension = buf.readResourceLocation();
        msg.x = buf.readDouble();
        msg.y = buf.readDouble();
        msg.z = buf.readDouble();
        return msg;
    }

    public static void handle(C2SSetGPSTarget msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            GPSTargetManager.Snapshot snapshot;
            if (msg.action == Action.CLEAR_ALL) {
                snapshot = GPSTargetManager.clear(player);
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return;
            }
            if (msg.action == Action.SET_MODE) {
                // [RVP] 服务端权威校验：模式必须在当前武器配置列表内且过传感器门禁，非法忽略并回发快照
                WeaponUnit unit = RVP_GpsModeSupport.operatedWeaponUnit(player);
                RVP_WeaponData weaponData = RVP_GpsModeSupport.currentRvpWeaponData(unit);
                if (!RVP_GpsModeSupport.isAvailable(unit, weaponData, msg.mode)) {
                    snapshot = GPSTargetManager.snapshot(player);
                } else {
                    snapshot = GPSTargetManager.setMode(player, msg.mode);
                }
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return;
            }
            // GPS距离最大半径限制
            double maxDist = 1.3407807929942596E154;
            Vec3 target = new Vec3(msg.x, msg.y, msg.z);
            if (player.position().distanceToSqr(target) > maxDist * maxDist) {
                snapshot = GPSTargetManager.snapshot(player);
                RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return;
            }
            if (msg.action == Action.FAST_SET) {
                // [RVP] FAST 开火键写点：覆盖单点且保持 FAST 模式
                snapshot = GPSTargetManager.fastSet(player, msg.dimension, target);
            } else {
                // [RVP] MULTI 追加上限取发送者当前武器配置（非 GPS 武器/无武器走默认 8）
                WeaponUnit unit = RVP_GpsModeSupport.operatedWeaponUnit(player);
                int maxPoints = RVP_GpsModeSupport.multiMaxPoints(RVP_GpsModeSupport.currentRvpWeaponData(unit));
                snapshot = msg.action == Action.ADD_POINT
                        ? GPSTargetManager.add(player, msg.dimension, target, maxPoints)
                        : GPSTargetManager.set(player, msg.dimension, target);
            }
            RVP_Network.CHANNEL.sendTo(S2CGpsStateSync.of(snapshot), player.connection.connection, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
        });
    }
}
