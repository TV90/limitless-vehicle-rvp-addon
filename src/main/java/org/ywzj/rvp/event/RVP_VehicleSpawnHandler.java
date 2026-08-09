package org.ywzj.rvp.event;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.all.AllItems;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 载具放置时（{@code spawnVehicleWithCreativeAmmo} 开启）：
 * <ol>
 *   <li>往载具库存塞一组创造模式弹药，供后续换弹使用；</li>
 *   <li>瞬间补满一次所有武器的所有弹药（跳过装填时间）。
 *       仅一次性操作：{@link EntityJoinLevelEvent} 只在载具加入世界的时刻触发一次，
 *       不随之后消耗弹药而重复触发。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_VehicleSpawnHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        LOGGER.info("[RVP][SpawnAmmo] entityJoin vehicle={} entityId={} config={} partUnits={}",
                vehicle.getVehicleId(), vehicle.getId(),
                RVP_CommonConfig.isSpawnVehicleWithCreativeAmmo(), vehicle.getPartUnits().size());
        if (!RVP_CommonConfig.isSpawnVehicleWithCreativeAmmo()) {
            return;
        }

        // 1. 载具库存插入一组创造模式弹药（供后续换弹使用）
        vehicle.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .filter(cap -> cap.getSlots() > 0)
                .ifPresent(cap -> {
                    ItemStack creativeAmmo = new ItemStack(AllItems.AMMO_CREATIVE.get(), 64);
                    for (int i = 0; i < cap.getSlots(); i++) {
                        if (cap.getStackInSlot(i).isEmpty()) {
                            cap.insertItem(i, creativeAmmo.copy(), false);
                            return;
                        }
                    }
                });

        // 2. 瞬间补满所有武器的所有弹药（仅生成时刻的一次性操作）
        refillAllWeapons(vehicle);

        // 3. 延迟到服务端下一 tick 再补一次：
        //    EntityJoinLevelEvent 触发时含代理部件（如 {"part_unit_id":"missile"}）的载具
        //    武器站可能尚未完成展开初始化，此时遍历会漏掉导弹等武器，导致"自动装弹失效"。
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                if (vehicle.isAlive() && vehicle.level() == serverLevel) {
                    refillAllWeapons(vehicle);
                }
            });
        }
    }

    /**
     * 遍历载具所有武器站的武器并瞬间补满弹药，跳过装填时间。
     * 直接设置剩余弹药 = 容量上限，不消耗库存弹药、不触发 reload。
     * <p>分组弹种（{@code merge_into_previous_slot} 合并）装配由
     * {@code WeaponUnitGroupedSlotMixin} 在 {@code WeaponUnit.combineAndInit} 阶段完成，
     * 早于本事件，此处补满的即装配后的新武器实例。</p>
     */
    private static void refillAllWeapons(AbstractVehicle vehicle) {
        Set<AbstractVehicleWeapon<?>> visited = new HashSet<>();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            refillWeaponList(weaponUnit.weapons, visited);
            refillWeaponList(weaponUnit.secondaryWeapons, visited);
            refillWeaponList(weaponUnit.independentWeapons, visited);
        }
    }

    private static void refillWeaponList(List<AbstractVehicleWeapon<?>> list, Set<AbstractVehicleWeapon<?>> visited) {
        for (AbstractVehicleWeapon<?> weapon : list) {
            if (!visited.add(weapon)) {
                continue;
            }
            // 多弹种武器：内部每个弹种子武器各自补满（子武器可能仍是多弹组，需递归）
            if (weapon instanceof VehicleMultiWeapons multi) {
                refillMulti(multi, visited);
                continue;
            }
            // 武器代理：真实弹药由代理目标武器站自身的遍历补满
            if (weapon instanceof VehicleWeaponAgent) {
                continue;
            }
            refillDirect(weapon);
        }
    }

    private static void refillMulti(VehicleMultiWeapons multi, Set<AbstractVehicleWeapon<?>> visited) {
        for (AbstractVehicleWeapon<?> sub : multi.getSubWeapons()) {
            if (!visited.add(sub)) {
                continue;
            }
            if (sub instanceof VehicleMultiWeapons nested) {
                refillMulti(nested, visited);
            } else if (!(sub instanceof VehicleWeaponAgent)) {
                // 直接对 multi 调 setRemainAmmo 无效：VehicleMultiWeapons.getRemainAmmo()
                // 委托给当前选中子武器，set 写入的字段不被读取 → 必须补到叶子武器上
                refillDirect(sub);
            }
        }
    }

    private static void refillDirect(AbstractVehicleWeapon<?> weapon) {
        int before = weapon.getRemainAmmo();
        int max = weapon.getMaxCapacity();
        weapon.setRemainAmmo(max);
        if (weapon instanceof RVP_WeaponBase rvpWeapon) {
            // 防御性清零装填倒计时（生成瞬间本应为 0，避免任何残留装填状态）
            rvpWeapon.ywzj_rvp$clearReloadState();
        }
        if (weapon.getVehicle() != null) {
            LOGGER.info("[RVP][SpawnAmmo] refill weapon={} before={} max={} after={}",
                    weapon.getData() == null || weapon.getData().getWeaponId() == null
                            ? "<null>" : weapon.getData().getWeaponId(),
                    before, max, weapon.getRemainAmmo());
        }
    }
}
