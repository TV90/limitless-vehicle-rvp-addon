package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：主动ECM HUD 同步（P1 占位，P4 再完善）。
 * 携带主动干扰剩余、冷却剩余及最大值，客户端更新 {@link org.ywzj.rvp.client.state.RVP_EcmActiveHudState}。
 */
public class S2CEcmActiveHudSync {

    /** 载具实体 id。 */
    private final int vehicleEntityId;
    /** 剩余主动时长（tick）。 */
    private final int activeRemainTick;
    /** 剩余冷却时长（tick）。 */
    private final int cooldownRemainTick;
    /** 主动时长最大值（tick）。 */
    private final int maxActiveTick;
    /** 冷却时长最大值（tick）。 */
    private final int maxCooldownTick;

    public S2CEcmActiveHudSync(int vehicleEntityId, int activeRemainTick, int cooldownRemainTick,
                               int maxActiveTick, int maxCooldownTick) {
        this.vehicleEntityId = vehicleEntityId;
        this.activeRemainTick = activeRemainTick;
        this.cooldownRemainTick = cooldownRemainTick;
        this.maxActiveTick = maxActiveTick;
        this.maxCooldownTick = maxCooldownTick;
    }

    public static void encode(S2CEcmActiveHudSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.vehicleEntityId);
        buf.writeVarInt(msg.activeRemainTick);
        buf.writeVarInt(msg.cooldownRemainTick);
        buf.writeVarInt(msg.maxActiveTick);
        buf.writeVarInt(msg.maxCooldownTick);
    }

    public static S2CEcmActiveHudSync decode(FriendlyByteBuf buf) {
        int vehicleEntityId = buf.readVarInt();
        int active = buf.readVarInt();
        int cooldown = buf.readVarInt();
        int maxActive = buf.readVarInt();
        int maxCooldown = buf.readVarInt();
        return new S2CEcmActiveHudSync(vehicleEntityId, active, cooldown, maxActive, maxCooldown);
    }

    public static void handle(S2CEcmActiveHudSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_EcmActiveHudState.update(
                        msg.vehicleEntityId, msg.activeRemainTick, msg.cooldownRemainTick,
                        msg.maxActiveTick, msg.maxCooldownTick)));
        ctx.get().setPacketHandled(true);
    }
}
