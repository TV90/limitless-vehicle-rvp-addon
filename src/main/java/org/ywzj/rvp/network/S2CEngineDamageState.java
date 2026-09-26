package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * [RVP] 引擎部件档位同步包（服务端 → 客户端，2026-09-26 新增，协议 17）。
 *
 * <p>{@code S2CBoneModuleState} 只有失效布尔集，表达不了"受损（功率减半）"这一中间档；
 * 本包按骨同步引擎档位：0=正常 / 1=受损（窗口累计伤害超受损阈值，功率 ×n）/ 2=瘫痪
 * （ENGINE 模块失效，功率清零）。瘫痪档同时会经 {@code S2CBoneModuleState} 广播失效状态
 * （面板红框 / dev 维修队列），本包负责受损档的可见性与面板档位文案。</p>
 *
 * <p>发送时机：{@code RVP_EnginePowerHandler} 每载具每 tick 差分比对，档位变化才发包。</p>
 */
public class S2CEngineDamageState {

    /** 引擎骨名 → 档位（0 正常 / 1 受损 / 2 瘫痪）。 */
    public int entityId;
    public Map<String, Integer> stages = Map.of();

    public static S2CEngineDamageState create(AbstractVehicle vehicle, Map<String, Integer> stages) {
        S2CEngineDamageState msg = new S2CEngineDamageState();
        msg.entityId = vehicle.getId();
        msg.stages = stages.isEmpty() ? Map.of() : new HashMap<>(stages);
        return msg;
    }

    public static void encode(S2CEngineDamageState msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeVarInt(msg.stages.size());
        for (var entry : msg.stages.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeVarInt(entry.getValue());
        }
    }

    public static S2CEngineDamageState decode(FriendlyByteBuf buf) {
        S2CEngineDamageState msg = new S2CEngineDamageState();
        msg.entityId = buf.readInt();
        int size = buf.readVarInt();
        Map<String, Integer> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(128), buf.readVarInt());
        }
        msg.stages = map;
        return msg;
    }

    public static void handle(S2CEngineDamageState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientEngineDamageState.apply(msg)));
    }
}
