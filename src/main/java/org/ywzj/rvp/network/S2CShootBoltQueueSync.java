package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.mount.RVP_ShootBoltQueueResolver;
import org.ywzj.vehicle.vehicle.pojo.Bolt;

import java.util.function.Supplier;

/**
 * [RVP] 服务端 → 客户端：同步"出弹队列"（可变挂架出弹点分离，shoot_structure_bones）。
 *
 * <p>出弹 Bolt 队列由服务端在数据重载期预计算（服务端结构模型百分百在册），本包把
 * 计算结果整体下发，客户端直接写入同一张队列表——客户端不需要也无法在运行期解析
 * 数据包侧结构模型（{@code CommonAssetsManager.structureModelManager()} 静态实例在
 * 客户端恒为资源重载产物），从根上绕开解析缺失问题。</p>
 *
 * <p>载荷为扁平条目流：每条 = 载具 id + 武器站 id + 武器口径 + Bolt 数量 + n×6 浮点
 * （offset xyz / barrelLength / xRot / yRot），与 {@link Bolt} 字段一一对应。</p>
 */
public class S2CShootBoltQueueSync {

    public S2CShootBoltQueueSync() {}

    public static void encode(S2CShootBoltQueueSync msg, FriendlyByteBuf buf) {
        // 目的：服务端把整张队列表编码下发
        RVP_ShootBoltQueueResolver.encodeQueue(buf);
    }

    public static S2CShootBoltQueueSync decode(FriendlyByteBuf buf) {
        // 目的：客户端收到后写入本地队列表
        RVP_ShootBoltQueueResolver.decodeQueue(buf);
        return new S2CShootBoltQueueSync();
    }

    public static void handle(S2CShootBoltQueueSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        // 队列表已在 decode 期写入，无需额外处理
    }
}
