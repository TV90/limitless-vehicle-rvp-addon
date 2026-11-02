package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 显式包声音的世界坐标兜底消息；只传武器标识，声音与距离参数由 display 决定。 */
public record S2CVehicleSound(ResourceLocation dimension, UUID vehicleId, int entityId,
                              Vec3 position, ResourceLocation weaponId) {
    public S2CVehicleSound {
        Objects.requireNonNull(dimension); Objects.requireNonNull(vehicleId); Objects.requireNonNull(weaponId);
        if (position == null || !Double.isFinite(position.x)
                || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Invalid configured vehicle sound");
    }
    /** 发送可独立播放的小快照；不要求接收方加载声源载具或该位置区块。 */
    public static void encode(S2CVehicleSound m, FriendlyByteBuf b) {
        b.writeResourceLocation(m.dimension); b.writeUUID(m.vehicleId); b.writeVarInt(m.entityId);
        b.writeDouble(m.position.x); b.writeDouble(m.position.y); b.writeDouble(m.position.z);
        b.writeResourceLocation(m.weaponId);
    }
    public static S2CVehicleSound decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); var vehicle = b.readUUID(); int entity = b.readVarInt();
        var position = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        return new S2CVehicleSound(dimension, vehicle, entity, position, b.readResourceLocation());
    }
    public static void handle(S2CVehicleSound m, Supplier<NetworkEvent.Context> supplier) {
        // 调用现有双端安全桥，专用服务端不会加载客户端声音类型。
        org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptVehicleSound(m);
        supplier.get().setPacketHandled(true);
    }
}
