package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * [RVP] 骨骼模块累计伤害进度同步（S2C，2026-09-28 全模块累计化配套）：
 * {@code entityId + 骨名 → 累计伤害}——辅助设备面板据此显示各模块的"虚拟血量"
 * （阈值 − 已累计）。服务端在通用累计入口差分推送（累计变化才发），
 * 模块状态广播（S2CBoneModuleState）时全量补发。
 */
public class S2CBoneDamageProgress {

    public int entityId;
    public Map<String, Float> damageByBone;

    public static S2CBoneDamageProgress create(int entityId, Map<String, Float> damageByBone) {
        S2CBoneDamageProgress msg = new S2CBoneDamageProgress();
        msg.entityId = entityId;
        msg.damageByBone = damageByBone == null ? Map.of() : damageByBone;
        return msg;
    }

    public static void encode(S2CBoneDamageProgress msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeVarInt(msg.damageByBone.size());
        for (Map.Entry<String, Float> entry : msg.damageByBone.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeFloat(entry.getValue());
        }
    }

    public static S2CBoneDamageProgress decode(FriendlyByteBuf buf) {
        S2CBoneDamageProgress msg = new S2CBoneDamageProgress();
        msg.entityId = buf.readInt();
        int size = buf.readVarInt();
        Map<String, Float> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(128), buf.readFloat());
        }
        msg.damageByBone = map;
        return msg;
    }

    public static void handle(S2CBoneDamageProgress msg, Supplier<NetworkEvent.Context> ctx) {
        // [RVP] 客户端侧表分发经 DistExecutor（lambda 内全限定名，不 import 客户端类——架构测试约束）
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () ->
                        org.ywzj.rvp.client.state.RVP_ClientBoneDamageProgress.apply(msg)));
        ctx.get().setPacketHandled(true);
    }
}
