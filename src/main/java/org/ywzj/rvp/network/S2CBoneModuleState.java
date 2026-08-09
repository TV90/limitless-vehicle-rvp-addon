package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 载具骨骼模块失效状态同步包（服务端 → 客户端）。
 *
 * <p>泛化了原 {@code S2CVehicleEraState}：每个骨块可携带多个失效模块类型
 * （如 {@code ERA0: [ERA, JAMMER]}），客户端据此让动画脚本查询
 * {@code rvp_isModuleActive}。</p>
 */
public class S2CBoneModuleState {

    public int entityId;
    /** 骨块名 → 已失效的模块类型集合。 */
    public Map<String, Set<BoneModuleType>> inactiveModules = Map.of();

    public static S2CBoneModuleState create(AbstractVehicle vehicle,
            Map<String, Set<BoneModuleType>> inactiveModules) {
        S2CBoneModuleState msg = new S2CBoneModuleState();
        msg.entityId = vehicle.getId();
        if (inactiveModules != null && !inactiveModules.isEmpty()) {
            Map<String, Set<BoneModuleType>> copy = new HashMap<>();
            for (var entry : inactiveModules.entrySet()) {
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            msg.inactiveModules = copy;
        }
        return msg;
    }

    public static void encode(S2CBoneModuleState msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeVarInt(msg.inactiveModules.size());
        for (var entry : msg.inactiveModules.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeVarInt(entry.getValue().size());
            for (BoneModuleType type : entry.getValue()) {
                buf.writeUtf(type.name(), 32);
            }
        }
    }

    public static S2CBoneModuleState decode(FriendlyByteBuf buf) {
        S2CBoneModuleState msg = new S2CBoneModuleState();
        msg.entityId = buf.readInt();
        int size = buf.readVarInt();
        Map<String, Set<BoneModuleType>> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String boneName = buf.readUtf(128);
            int typeCount = buf.readVarInt();
            Set<BoneModuleType> types = java.util.EnumSet.noneOf(BoneModuleType.class);
            for (int j = 0; j < typeCount; j++) {
                BoneModuleType type = BoneModuleType.byName(buf.readUtf(32));
                if (type != null) {
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                map.put(boneName, types);
            }
        }
        msg.inactiveModules = map;
        return msg;
    }

    public static void handle(S2CBoneModuleState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientBoneModuleState.apply(msg)));
    }
}
