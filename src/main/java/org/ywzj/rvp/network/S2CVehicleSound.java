package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.data.RVP_FireSoundData;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 显式包声音的世界坐标兜底消息；事件名、近远阈值和音量均来自 weapon JSON。 */
public record S2CVehicleSound(ResourceLocation dimension, UUID vehicleId, int entityId,
                              Vec3 position, RVP_FireSoundData config) {
    public S2CVehicleSound {
        Objects.requireNonNull(dimension); Objects.requireNonNull(vehicleId); Objects.requireNonNull(config);
        if (!config.isConfigured() || position == null || !Double.isFinite(position.x)
                || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Invalid configured vehicle sound");
    }
    /** 发送可独立播放的小快照；不要求接收方加载声源载具或该位置区块。 */
    public static void encode(S2CVehicleSound m, FriendlyByteBuf b) {
        b.writeResourceLocation(m.dimension); b.writeUUID(m.vehicleId); b.writeVarInt(m.entityId);
        b.writeDouble(m.position.x); b.writeDouble(m.position.y); b.writeDouble(m.position.z);
        b.writeResourceLocation(m.config.getSound()); b.writeBoolean(m.config.getFarSound() != null);
        if (m.config.getFarSound() != null) b.writeResourceLocation(m.config.getFarSound());
        b.writeFloat(m.config.getFarDistance()); b.writeFloat(m.config.getMaxDistance());
        b.writeFloat(m.config.getVolume()); b.writeFloat(m.config.getPitch());
    }
    public static S2CVehicleSound decode(FriendlyByteBuf b) {
        var dimension = b.readResourceLocation(); var vehicle = b.readUUID(); int entity = b.readVarInt();
        var position = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        var sound = b.readResourceLocation(); var far = b.readBoolean() ? b.readResourceLocation() : null;
        var config = new RVP_FireSoundData(sound, far, b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
        return new S2CVehicleSound(dimension, vehicle, entity, position, config);
    }
    public static void handle(S2CVehicleSound m, Supplier<NetworkEvent.Context> supplier) {
        // 调用现有双端安全桥，专用服务端不会加载客户端声音类型。
        org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptVehicleSound(m);
        supplier.get().setPacketHandled(true);
    }
}
