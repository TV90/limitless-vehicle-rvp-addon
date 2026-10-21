package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.guidance.RVP_IrLockHelper;
import org.ywzj.rvp.weapon.core.RVP_ServerIrSeekerArmedTable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.function.Supplier;

/**
 * [RVP] IR 导引头 armed 跃迁上报（2026-10-21「连续跟踪锁定」）：客户端 tracker 仅在
 * 状态跃迁（落锁/掉锁）时发送，不做每 tick 上报。服务端不信任包内容——收包即复算：
 * 玩家确为该载具该站的操作者、weaponId 与当前弹种一致、目标存活同维度、
 * 仍在本弹 hold 包线内（离轴/距离/高度/LOS 服务端可算），全部通过才写服务端 armed 表。
 */
public class C2SIrSeekerArmed {
    /** 载具实体 id。 */
    public int vehicleId;
    /** 根武器站在 vehicle.getPartUnits() 的序号（与 RVP_ProjectileSpawner.stationIndexOf 同语义）。 */
    public int stationIndex;
    /** 弹种 id（防切弹种串状态）。 */
    public ResourceLocation weaponId;
    /** armed 目标实体 id；解除传 -1。 */
    public int entityId;
    /** true=落锁 / false=解锁。 */
    public boolean armed;

    public C2SIrSeekerArmed() {}

    public C2SIrSeekerArmed(int vehicleId, int stationIndex, ResourceLocation weaponId,
                            int entityId, boolean armed) {
        this.vehicleId = vehicleId;
        this.stationIndex = stationIndex;
        this.weaponId = weaponId;
        this.entityId = entityId;
        this.armed = armed;
    }

    public static void encode(C2SIrSeekerArmed msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleId);
        buf.writeInt(msg.stationIndex);
        buf.writeResourceLocation(msg.weaponId);
        buf.writeInt(msg.entityId);
        buf.writeBoolean(msg.armed);
    }

    public static C2SIrSeekerArmed decode(FriendlyByteBuf buf) {
        return new C2SIrSeekerArmed(buf.readInt(), buf.readInt(),
                buf.readResourceLocation(), buf.readInt(), buf.readBoolean());
    }

    public static void handle(C2SIrSeekerArmed msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.getVehicle() == null) {
                return;
            }
            // ① 玩家确为该载具的操作者，且操作站归并到 msg.stationIndex 指向的根站。
            if (!(player.getVehicle() instanceof AbstractVehicle vehicle)
                    || vehicle.getId() != msg.vehicleId) {
                return;
            }
            PartUnit<?> operator = vehicle.getOwnOperatorUnit(player);
            if (!(operator instanceof WeaponUnit operatorUnit)) {
                return;
            }
            WeaponUnit root = operatorUnit.getRootParentWeaponUnit();
            if (root == null || stationIndexOf(vehicle, root) != msg.stationIndex) {
                return;
            }
            // ② weaponId 与当前弹种一致（防切弹种串状态）；且必须是 IR 发射武器。
            // 2026-10-21 夜间修复（诊断 D5）：多弹种 / agent 站当前武器是 VehicleMultiWeapons /
            // VehicleWeaponAgent，不解包则恒不是 RVP_WeaponBase ⇒ 所有包被丢弃、服务端表永远为空。
            AbstractVehicleWeapon<?> current = operatorUnit.getCurrentWeapon().orElse(null);
            AbstractVehicleWeapon<?> resolvedCurrent = org.ywzj.rvp.util.RVP_WeaponResolveHelper.unwrap(current);
            if (!(resolvedCurrent instanceof RVP_WeaponBase rvpWeapon)
                    || !msg.weaponId.equals(rvpWeapon.getData().getWeaponId())
                    || !RVP_IrLockHelper.isIrLaunchWeapon(rvpWeapon.getData())) {
                return;
            }
            // ③ 解除：直接清表项（无目标可校验）。
            if (!msg.armed || msg.entityId == -1) {
                RVP_ServerIrSeekerArmedTable.disarm(root, msg.weaponId);
                return;
            }
            // ④ 目标存活 + 同维度。
            Entity target = player.serverLevel().getEntity(msg.entityId);
            if (target == null || !target.isAlive()
                    || target.level().dimension() != player.serverLevel().dimension()) {
                return;
            }
            // ⑤ 包线复算（服务端可算：worldPivotPosition/worldVec；含离轴/距离/高度/LOS）。
            RVP_WeaponData data = rvpWeapon.getData();
            // 几何复算用"武器自己的站"（与 tracker / 客户端门控同源），根站只作表键。
            WeaponUnit geo = rvpWeapon.getWeaponUnit() != null ? rvpWeapon.getWeaponUnit() : root;
            if (!RVP_IrLockHelper.isTargetWithinHoldLimits(geo, target, data)) {
                return;
            }
            // 全部通过 → 写服务端 armed 表。
            RVP_ServerIrSeekerArmedTable.arm(root, msg.weaponId,
                    target.getId(), root.getVehicle().level().getGameTime());
        });
    }

    /** 根站在载具部件表中的序号（与 RVP_ProjectileSpawner.stationIndexOf 同语义）。 */
    private static int stationIndexOf(AbstractVehicle vehicle, WeaponUnit root) {
        for (int i = 0; i < vehicle.getPartUnits().size(); i++) {
            if (vehicle.getPartUnits().get(i) == root) {
                return i;
            }
        }
        return -1;
    }
}
