package org.ywzj.rvp.entity.gunner.behavior.action;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 地面 Gunner 的随机切弹纪律：当前弹种累计射击 3-9 发后，随机换到同组另一种有弹的弹种。
 *
 * <p>不做目标类型/弹种能力分类——破甲弹与炮射导弹同样有反装甲能力，动能弹也可能是纯反步兵弹，
 * 现阶段用目标类别猜弹容易误判。这里只保证“不一直打同一种弹”，并按 F 键切弹的语义
 * 清空旧弹种、让新弹种重新装填。</p>
 */
public final class RVP_GunnerAmmoSelector {

    /** 切弹前最少射击发数。 */
    private static final int SWITCH_SHOT_MIN = 3;
    /** 切弹前最多射击发数。 */
    private static final int SWITCH_SHOT_MAX = 9;

    private RVP_GunnerAmmoSelector() {}

    /** 记录一发实际发射；达到本轮随机门限时切换一次弹种。 */
    public static void onShotFired(GunnerEntity gunner,
                                   AbstractVehicle vehicle,
                                   Entity target,
                                   AbstractVehicleWeapon<?> topWeapon,
                                   int firedRounds) {
        if (gunner == null || vehicle == null || topWeapon == null || firedRounds <= 0) {
            return;
        }
        if (vehicle.level().isClientSide()
                || target instanceof AmmoEntity
                || !(vehicle instanceof TrackedVehicle || vehicle instanceof WheeledVehicle)) {
            return;
        }
        if (!(topWeapon instanceof VehicleMultiWeapons multi)) {
            return;
        }
        List<LeafPath> leaves = collectLeaves(multi);
        if (leaves.size() <= 1) {
            return;
        }
        int weaponKey = System.identityHashCode(multi);
        if (gunner.getAmmoSwitchWeaponKey() != weaponKey
                || gunner.getAmmoSwitchShotThreshold() <= 0) {
            gunner.setAmmoSwitchWeaponKey(weaponKey);
            gunner.setAmmoSwitchShotCount(0);
            gunner.setAmmoSwitchShotThreshold(nextThreshold(gunner));
        }
        gunner.setAmmoSwitchShotCount(gunner.getAmmoSwitchShotCount() + firedRounds);
        if (gunner.getAmmoSwitchShotCount() < gunner.getAmmoSwitchShotThreshold()) {
            return;
        }
        // 新一轮门限无论是否成功切换都重新抽，避免连续每发都尝试。
        gunner.setAmmoSwitchShotCount(0);
        gunner.setAmmoSwitchShotThreshold(nextThreshold(gunner));

        LeafPath current = currentLeaf(multi);
        List<LeafPath> candidates = new ArrayList<>(leaves.size());
        for (LeafPath leaf : leaves) {
            if (!leaf.path().equals(current.path()) && leaf.weapon().hasAmmo()) {
                candidates.add(leaf);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        LeafPath selected = candidates.get(gunner.getRandom().nextInt(candidates.size()));
        switchTo(vehicle, multi, current.weapon(), selected);
        // 主动切弹造成的装填不是“打完弹后的反击装填”，不触发换弹起射延迟。
        gunner.resetReloadSettle();
    }

    /** 递归收集 multi 下所有叶子武器及其选择路径。 */
    private static List<LeafPath> collectLeaves(VehicleMultiWeapons top) {
        List<LeafPath> out = new ArrayList<>();
        collectLeaves(top, List.of(), out);
        return out;
    }

    private static void collectLeaves(VehicleMultiWeapons multi, List<Integer> prefix, List<LeafPath> out) {
        List<AbstractVehicleWeapon<?>> subWeapons = multi.getSubWeapons();
        for (int index = 0; index < subWeapons.size(); index++) {
            AbstractVehicleWeapon<?> sub = subWeapons.get(index);
            List<Integer> path = new ArrayList<>(prefix);
            path.add(index);
            if (sub instanceof VehicleMultiWeapons nested) {
                collectLeaves(nested, path, out);
            } else {
                out.add(new LeafPath(List.copyOf(path), sub));
            }
        }
    }

    /** 当前选中叶子及其路径。 */
    private static LeafPath currentLeaf(VehicleMultiWeapons top) {
        List<Integer> path = new ArrayList<>();
        AbstractVehicleWeapon<?> current = top;
        while (current instanceof VehicleMultiWeapons multi) {
            int selected = multi.getSelectedIndex();
            if (selected < 0 || selected >= multi.getSubWeapons().size()) {
                break;
            }
            path.add(selected);
            current = multi.getSubWeapons().get(selected);
        }
        return new LeafPath(List.copyOf(path), current);
    }

    /** 按路径一次性设置每一层 selectedIndex，避免多次 cycle 把中间弹种也清空。 */
    private static void switchTo(AbstractVehicle vehicle, VehicleMultiWeapons top,
                                 AbstractVehicleWeapon<?> oldLeaf, LeafPath target) {
        while (oldLeaf instanceof VehicleMultiWeapons nested && !nested.getSubWeapons().isEmpty()) {
            oldLeaf = nested.getSelectedWeapon();
        }
        ejectOldAmmo(vehicle, oldLeaf);
        VehicleMultiWeapons multi = top;
        AbstractVehicleWeapon<?> newLeaf = target.weapon();
        for (int index : target.path()) {
            if (index < 0 || index >= multi.getSubWeapons().size()) {
                return;
            }
            multi.setSelectedIndex(index);
            AbstractVehicleWeapon<?> sub = multi.getSubWeapons().get(index);
            if (sub instanceof VehicleMultiWeapons nested) {
                multi = nested;
            }
            newLeaf = sub;
        }
        while (newLeaf instanceof VehicleMultiWeapons nested && !nested.getSubWeapons().isEmpty()) {
            newLeaf = nested.getSelectedWeapon();
        }
        // 与本体 F 键切弹一致：新弹种需要重新装填，不能带着旧弹直接开火。
        newLeaf.setRemainAmmo(0);
        if (newLeaf.getData() != null && newLeaf.getData().getReload().getTime() > 0) {
            newLeaf.startReload();
        }
    }

    /** 与本体 VehicleMultiWeapons.cycleSubWeapon 相同的旧弹返还逻辑。 */
    private static void ejectOldAmmo(AbstractVehicle vehicle, AbstractVehicleWeapon<?> oldWeapon) {
        int oldAmmo = oldWeapon.getRemainAmmo();
        if (oldAmmo > 0 && !vehicle.level().isClientSide() && oldWeapon.getData().getReload() != null) {
            ItemStack[] matchingItems = oldWeapon.getData().getReload().getAmmo().getItems();
            if (matchingItems.length > 0) {
                AtomicReference<ItemStack> returnStack = new AtomicReference<>(matchingItems[0].copy());
                returnStack.get().setCount(oldAmmo);
                vehicle.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(cap -> {
                    for (int i = 0; i < cap.getSlots(); i++) {
                        if (cap.getStackInSlot(i).getItem() == AllItems.AMMO_CREATIVE.get()) {
                            returnStack.set(ItemStack.EMPTY);
                            return;
                        }
                    }
                    for (int i = 0; i < cap.getSlots(); i++) {
                        returnStack.set(cap.insertItem(i, returnStack.get(), false));
                        if (returnStack.get().isEmpty()) {
                            break;
                        }
                    }
                });
                if (!returnStack.get().isEmpty()) {
                    vehicle.spawnAtLocation(returnStack.get());
                }
            }
        }
        oldWeapon.setRemainAmmo(0);
    }

    private static int nextThreshold(GunnerEntity gunner) {
        return SWITCH_SHOT_MIN + gunner.getRandom().nextInt(SWITCH_SHOT_MAX - SWITCH_SHOT_MIN + 1);
    }

    private record LeafPath(List<Integer> path, AbstractVehicleWeapon<?> weapon) {}
}
