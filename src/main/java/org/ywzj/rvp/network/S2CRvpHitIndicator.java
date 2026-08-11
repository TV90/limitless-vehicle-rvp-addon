package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * RVP 命中提示：RVP 弹体命中载具时向射手本人推送命中数据（右上角展板 UI）。
 * <p>骨骼显示别名由服务端用 {@code hitbox_display_name} 解析好后再下发（客户端无该数据）。</p>
 */
public class S2CRvpHitIndicator {

    public int entityId;
    public Vec3 hitPosition;
    public Vec3 hitVector;
    public float damage;
    public String boneDisplayName;
    public String ammoNameKey;
    /** 武器 id（可空）：客户端据此查询武器 display 判断“有无模型”，决定命中动画渲染模型还是曳光 */
    public String weaponId;
    /**
     * 命中时刻的被命中实体位置（世界坐标）：超视距实体由广播克隆体渲染，克隆体位置可能滞后，
     * 客户端用它对齐“模型 vs 命中特效”，避免高速移动目标上特效相对模型偏移出展示框。
     */
    public Vec3 entityPosAtHit;
    /**
     * 被命中载具的 displayId（可空，仅 AbstractVehicle 有）：超视距广播数据不含 displayId，
     * 克隆实体渲染时 VehicleRender 会因 displayId 为 null 直接跳过 —— 客户端用它初始化克隆实体。
     */
    public String vehicleDisplayId;
    /**
     * 爆炸半径（方块）：>0 表示该命中带爆炸（直击 HE / 爆炸波及 / 近炸），客户端据此
     * 渲染"随爆炸范围扩大的扩散圈"（至多 5 倍基础圈，radius=20 时封顶）；0 = 无爆炸信息。
     */
    public float explosionRadius;

    public static S2CRvpHitIndicator create(int entityId, Vec3 hitPosition, Vec3 hitVector,
                                            float damage, String boneDisplayName, String ammoNameKey,
                                            String weaponId, Vec3 entityPosAtHit, String vehicleDisplayId,
                                            float explosionRadius) {
        S2CRvpHitIndicator msg = new S2CRvpHitIndicator();
        msg.entityId = entityId;
        msg.hitPosition = hitPosition;
        msg.hitVector = hitVector;
        msg.damage = damage;
        msg.boneDisplayName = boneDisplayName == null ? "" : boneDisplayName;
        msg.ammoNameKey = ammoNameKey == null ? "" : ammoNameKey;
        msg.weaponId = weaponId == null ? "" : weaponId;
        msg.entityPosAtHit = entityPosAtHit == null ? hitPosition : entityPosAtHit;
        msg.vehicleDisplayId = vehicleDisplayId == null ? "" : vehicleDisplayId;
        msg.explosionRadius = explosionRadius;
        return msg;
    }

    public static void encode(S2CRvpHitIndicator msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeVector3f(msg.hitPosition.toVector3f());
        buf.writeVector3f(msg.hitVector.toVector3f());
        buf.writeFloat(msg.damage);
        buf.writeUtf(msg.boneDisplayName, 128);
        buf.writeUtf(msg.ammoNameKey, 128);
        buf.writeUtf(msg.weaponId, 128);
        buf.writeVector3f(msg.entityPosAtHit.toVector3f());
        buf.writeUtf(msg.vehicleDisplayId, 128);
        buf.writeFloat(msg.explosionRadius);
    }

    public static S2CRvpHitIndicator decode(FriendlyByteBuf buf) {
        S2CRvpHitIndicator msg = new S2CRvpHitIndicator();
        msg.entityId = buf.readInt();
        msg.hitPosition = new Vec3(buf.readVector3f());
        msg.hitVector = new Vec3(buf.readVector3f());
        msg.damage = buf.readFloat();
        msg.boneDisplayName = buf.readUtf(128);
        msg.ammoNameKey = buf.readUtf(128);
        msg.weaponId = buf.readUtf(128);
        msg.entityPosAtHit = new Vec3(buf.readVector3f());
        msg.vehicleDisplayId = buf.readUtf(128);
        msg.explosionRadius = buf.readFloat();
        return msg;
    }

    public static void handle(S2CRvpHitIndicator msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                org.ywzj.rvp.client.state.RVP_ClientHitIndicatorState.push(msg)));
    }
}
