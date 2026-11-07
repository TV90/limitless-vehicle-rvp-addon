package org.ywzj.rvp.uav;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 部署辅助载具的自动补给：每 tick 回满燃油，并在弹药耗尽后按原装填时间补满。
 *
 * <p>与 Gunner 司机补给保持同一节奏（耗尽 → 等原装填时间 → 补满），
 * 因此“无限弹药”不是无装填连发；玩家驾驶/无人悬停期间都生效。</p>
 */
public final class RVP_DeployableSupplyService {
    /** 补给适配器日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 无弹武器到达再次补满时刻的弱引用表，单位为系统毫秒。 */
    private static final Map<AbstractVehicleWeapon<?>, Long> AMMO_READY_TIME = new WeakHashMap<>();
    /** 本体武器私有装填时间方法的缓存；RVP 武器走公开桥接。 */
    @Nullable
    private static Method setReloadTimeMethod;
    /** 是否已经尝试解析本体装填方法，避免失败后每 tick 重复反射和刷日志。 */
    private static boolean setReloadTimeMethodResolved;

    private RVP_DeployableSupplyService() {}

    /** 新部署时立即回满燃油和全部有效武器弹药。 */
    public static void initialize(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide() || vehicle.isDestroyed()) {
            return;
        }
        // 调用本体能量接口，新部署辅助载具不继承母车剩余油量。
        vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
        forEachValidWeapon(vehicle, weapon -> {
            weapon.setRemainAmmo(Math.max(1, weapon.getMaxCapacity()));
            forceSetReloadTime(weapon, 0);
            AMMO_READY_TIME.remove(weapon);
        });
    }

    /** 每 tick 维持燃油与弹药；只对已登记的可部署子载具调用。 */
    public static void tick(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.level().isClientSide()
                || vehicle.isDestroyed() || vehicle.isRemoved()) {
            return;
        }
        vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
        long now = System.currentTimeMillis();
        forEachValidWeapon(vehicle, weapon -> {
            if (weapon.getRemainAmmo() > 0) {
                AMMO_READY_TIME.remove(weapon);
                return;
            }
            Long readyTime = AMMO_READY_TIME.get(weapon);
            if (readyTime == null) {
                readyTime = now + getReloadMillis(weapon);
                AMMO_READY_TIME.put(weapon, readyTime);
            }
            long remainMillis = Math.max(0L, readyTime - now);
            if (remainMillis > 0L) {
                forceSetReloadTime(weapon, millisToTicks(remainMillis));
                return;
            }
            weapon.setRemainAmmo(Math.max(1, weapon.getMaxCapacity()));
            forceSetReloadTime(weapon, 0);
            AMMO_READY_TIME.remove(weapon);
        });
    }

    /** 对载具全部有效武器执行补给动作；无 weapon_id 的占位槽不处理。 */
    private static void forEachValidWeapon(AbstractVehicle vehicle,
                                           java.util.function.Consumer<AbstractVehicleWeapon<?>> consumer) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : weaponUnit.getIndexedWeapons()) {
                if (weapon.getData().getWeaponId() != null) {
                    consumer.accept(weapon);
                } else {
                    AMMO_READY_TIME.remove(weapon);
                }
            }
        }
    }

    /** 对 RVP 武器使用公开入口，对本体武器集中执行唯一的兼容反射。 */
    private static void forceSetReloadTime(AbstractVehicleWeapon<?> weapon, int ticks) {
        if (weapon instanceof RVP_WeaponBase rvpWeapon) {
            rvpWeapon.ywzj_rvp$setReloadTime(ticks);
            return;
        }
        if (!setReloadTimeMethodResolved) {
            setReloadTimeMethodResolved = true;
            try {
                setReloadTimeMethod = ObfuscationReflectionHelper.findMethod(
                        AbstractVehicleWeapon.class, "setReloadTime", int.class);
                setReloadTimeMethod.setAccessible(true);
            } catch (Throwable throwable) {
                LOGGER.warn("[RVP_DeployableSupplyService] 无法解析本体 setReloadTime，补给装填覆盖将安全降级", throwable);
                return;
            }
        }
        if (setReloadTimeMethod == null) {
            return;
        }
        try {
            setReloadTimeMethod.invoke(weapon, ticks);
        } catch (Throwable throwable) {
            LOGGER.debug("[RVP_DeployableSupplyService] 调用本体 setReloadTime 失败", throwable);
        }
    }

    /** 计算武器再次补满前应等待的毫秒数；单发武器额外等一个射击间隔，避免瞬间连发。 */
    private static long getReloadMillis(AbstractVehicleWeapon<?> weapon) {
        long reloadMillis = Math.max(0, weapon.getData().getReload().getTime()) * 50L;
        long cooldownMillis = Math.max(0L, weapon.getShootInterval());
        return weapon.getMaxCapacity() <= 1 ? reloadMillis + cooldownMillis : reloadMillis;
    }

    /** 将剩余毫秒向上换算为至少一个 tick。 */
    private static int millisToTicks(long millis) {
        return Math.max(1, (int) ((millis + 49L) / 50L));
    }
}
