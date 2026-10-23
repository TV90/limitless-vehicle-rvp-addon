package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.weapon.impact.RVP_VehicleImpactSparks;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 载具直接命中的纯视觉快照，不依赖射手或弹体是否仍存在。
 * @param dimension 命中所在维度，避免切维度时迟到包串场
 * @param position 命中装甲表面的世界坐标，单位格
 * @param normal 装甲表面朝外法线，客户端据此向外喷射火星
 * @param incomingDirection 跳弹或伤害结算前的弹丸前进方向，世界坐标单位向量；零向量回退正面命中
 * @param caliber 物理口径，单位毫米，合法范围 1～200
 */
public record S2CVehicleImpactSparks(ResourceLocation dimension, Vec3 position, Vec3 normal,
                                   Vec3 incomingDirection, float caliber) {
    public S2CVehicleImpactSparks {
        Objects.requireNonNull(dimension, "dimension");
        // 校验所有网络浮点输入，阻止无效位置或尺寸进入粒子引擎。
        if (!finite(position) || !finite(normal) || !finite(incomingDirection) || !Float.isFinite(caliber)
                || caliber < 1f || caliber > RVP_VehicleImpactSparks.MAX_CALIBER) {
            throw new IllegalArgumentException("Invalid vehicle impact sparks");
        }
    }

    /** 编码服务端命中快照。 */
    public static void encode(S2CVehicleImpactSparks message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimension);
        buffer.writeDouble(message.position.x);
        buffer.writeDouble(message.position.y);
        buffer.writeDouble(message.position.z);
        buffer.writeDouble(message.normal.x);
        buffer.writeDouble(message.normal.y);
        buffer.writeDouble(message.normal.z);
        buffer.writeDouble(message.incomingDirection.x);
        buffer.writeDouble(message.incomingDirection.y);
        buffer.writeDouble(message.incomingDirection.z);
        buffer.writeFloat(message.caliber);
    }

    /** 解码并通过构造器统一验证快照。 */
    public static S2CVehicleImpactSparks decode(FriendlyByteBuf buffer) {
        return new S2CVehicleImpactSparks(buffer.readResourceLocation(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readFloat());
    }

    /** 网络主线程转交双端安全桥，公共消息类不引用客户端实现。 */
    public static void handle(S2CVehicleImpactSparks message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // 调用 RVP 安全桥，在客户端主线程生成粒子。
        context.enqueueWork(() -> org.ywzj.rvp.client.bridge.RVP_ClientActionsAccess.acceptVehicleImpactSparks(message));
        context.setPacketHandled(true);
    }

    /** 检查三轴坐标均为有限数。 */
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
