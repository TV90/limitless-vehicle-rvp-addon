package org.ywzj.rvp.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.function.Supplier;

/**
 * 改装工具选择多弹种槽的子武器。
 *
 * @param vehicleEntityId 载具实体 id
 * @param partId          武器部件 id
 * @param weaponIndex     部件内武器槽下标
 * @param subWeaponIndex  组内子武器下标
 * @param groupIndex      分组载波外层 multi 的组下标；<b>-1</b> 表示普通
 *                        {@code modding_only_multi} 整槽单组（兼容旧寻址）。
 *                        ≥0 时目标 multi = 外层 multi 的第 groupIndex 个子武器，
 *                        且选中后会把外层组切换为该组（使装填弹种生效）。
 */
public record C2SSelectModdingSubWeapon(
        int vehicleEntityId,
        String partId,
        int weaponIndex,
        int subWeaponIndex,
        int groupIndex
) {

    private static final double MAX_INTERACTION_DISTANCE_SQ = 256.0;

    public static void encode(C2SSelectModdingSubWeapon msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleEntityId);
        buf.writeUtf(msg.partId);
        buf.writeInt(msg.weaponIndex);
        buf.writeInt(msg.subWeaponIndex);
        buf.writeInt(msg.groupIndex);
    }

    public static C2SSelectModdingSubWeapon decode(FriendlyByteBuf buf) {
        return new C2SSelectModdingSubWeapon(
                buf.readInt(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt()
        );
    }

    public static void handle(C2SSelectModdingSubWeapon msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        context.setPacketHandled(true);
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || !hasModdingTool(player.getItemInHand(InteractionHand.MAIN_HAND), player.getItemInHand(InteractionHand.OFF_HAND))) {
                return;
            }
            if (!(player.level().getEntity(msg.vehicleEntityId) instanceof AbstractVehicle vehicle)) {
                return;
            }
            if (player.distanceToSqr(vehicle) > MAX_INTERACTION_DISTANCE_SQ) {
                return;
            }
            // 改装限制：仅当载具速度低于 5 kph 且车上无玩家或 gunner 时才允许更换武器（服务端权威校验）
            if (!RVP_VehicleExtendedConfigManager.INSTANCE.canModVehicle(vehicle)) {
                return;
            }
            PartUnit<?> partUnit = vehicle.getPartUnit(msg.partId).orElse(null);
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                return;
            }
            if (!RVP_VehicleExtendedConfigManager.INSTANCE.isModdingOnlyMulti(weaponUnit, msg.weaponIndex)) {
                return;
            }
            if (msg.weaponIndex < 0 || msg.weaponIndex >= weaponUnit.weapons.size()) {
                return;
            }
            VehicleMultiWeapons multi = RVP_VehicleExtendedConfigManager.INSTANCE
                    .resolveModdingTargetMulti(weaponUnit, msg.weaponIndex);
            if (multi == null) {
                return;
            }
            if (msg.groupIndex >= 0) {
                // 分组载波（T90M/VT4 的 AP+HE 并槽）：目标 multi 是外层组的第 groupIndex 个子武器。
                // 注意 resolveModdingTargetMulti 对 grouped carrier 已返回内层（首个嵌套 multi），
                // 组序号须在真正的外层上寻址，这里从武器槽顶层重新取外层。
                AbstractVehicleWeapon<?> topLevel = weaponUnit.weapons.get(msg.weaponIndex);
                if (!(topLevel instanceof VehicleMultiWeapons outer)) {
                    return;
                }
                if (msg.groupIndex >= outer.getSubWeapons().size()
                        || !(outer.getSubWeapons().get(msg.groupIndex) instanceof VehicleMultiWeapons groupMulti)) {
                    return;
                }
                if (msg.subWeaponIndex < 0 || msg.subWeaponIndex >= groupMulti.getSubWeapons().size()) {
                    return;
                }
                selectVariant(groupMulti, msg.subWeaponIndex);
                // 外层组同步切到该组：否则当前激活组仍是另一组，装填/开火的还是旧弹种
                selectVariant(outer, msg.groupIndex);
                return;
            }
            if (msg.subWeaponIndex < 0 || msg.subWeaponIndex >= multi.getSubWeapons().size()) {
                return;
            }
            selectVariant(multi, msg.subWeaponIndex);
        });
    }

    private static void selectVariant(VehicleMultiWeapons multi, int targetIndex) {
        int current = multi.getSelectedIndex();
        if (current == targetIndex) {
            return;
        }
        int guard = multi.getSubWeapons().size() + 1;
        while (multi.getSelectedIndex() != targetIndex && guard-- > 0) {
            multi.cycleSubWeapon(true);
        }
    }

    private static boolean hasModdingTool(ItemStack mainHand, ItemStack offHand) {
        return mainHand.is(AllItems.MODDING_TOOL.get()) || offHand.is(AllItems.MODDING_TOOL.get());
    }
}
