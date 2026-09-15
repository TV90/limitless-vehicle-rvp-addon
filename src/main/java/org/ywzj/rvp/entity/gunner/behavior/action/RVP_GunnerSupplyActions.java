package org.ywzj.rvp.entity.gunner.behavior.action;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/** 隔离 Gunner 司机首次补满、持续无限弹药与本体武器反射兼容。 */
public final class RVP_GunnerSupplyActions {

    /** 补给适配器日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 无弹武器到达再次补满时刻的弱引用表，单位为系统毫秒。 */
    private static final Map<AbstractVehicleWeapon<?>, Long> AMMO_READY_TIME = new WeakHashMap<>();
    /** 本体武器私有装填时间方法的缓存；RVP 武器不使用反射。 */
    @Nullable
    private static Method setReloadTimeMethod;
    /** 是否已经尝试解析本体装填方法，避免失败后每 tick 重复反射和刷日志。 */
    private static boolean setReloadTimeMethodResolved;

    RVP_GunnerSupplyActions() {
    }

    /** 首次成为司机时补满能源和全部有效武器，并记录返航锚点。 */
    public RVP_GunnerActionResult refillOnDriverEnter(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (gunner == null || vehicle == null || !isDriver(vehicle, gunner)) {
            return RVP_GunnerActionResult.NOT_DRIVER;
        }
        if (!gunner.markDriverRide(vehicle.getId())) {
            return RVP_GunnerActionResult.GATED;
        }
        if (!gunner.hasHomePos()) {
            gunner.setHomePos(vehicle.position());
        }
        // 调用本体发动机公开 API，完成 Gunner 司机首次接管。
        vehicle.toggleEngine(true);
        vehicle.setEnergy(vehicle.energyInfo.energyCapacity);
        forEachValidWeapon(vehicle, weapon -> {
            weapon.setRemainAmmo(weapon.getMaxCapacity());
            forceSetReloadTime(weapon, 0);
        });
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 按武器原始装填时间持续维持司机无限弹药。 */
    public RVP_GunnerActionResult sustainDriverAmmo(GunnerEntity gunner, AbstractVehicle vehicle) {
        if (gunner == null || vehicle == null || !isDriver(vehicle, gunner)) {
            return RVP_GunnerActionResult.NOT_DRIVER;
        }
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
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** Gunner 不再具备司机补给资格时清除该车补给计时并归零兼容装填覆盖。 */
    public RVP_GunnerActionResult clearDriverAmmoTimers(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return RVP_GunnerActionResult.INVALID;
        }
        forEachValidWeapon(vehicle, weapon -> {
            AMMO_READY_TIME.remove(weapon);
            forceSetReloadTime(weapon, 0);
        });
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 对载具的全部有效武器执行补给动作。 */
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
                LOGGER.warn("[RVP_GunnerSupplyActions] 无法解析本体 setReloadTime，补给装填覆盖将安全降级", throwable);
                return;
            }
        }
        if (setReloadTimeMethod == null) {
            return;
        }
        try {
            setReloadTimeMethod.invoke(weapon, ticks);
        } catch (Throwable throwable) {
            LOGGER.debug("[RVP_GunnerSupplyActions] 调用本体 setReloadTime 失败", throwable);
        }
    }

    /** 计算武器再次补满前应等待的毫秒数。 */
    private static long getReloadMillis(AbstractVehicleWeapon<?> weapon) {
        long reloadMillis = Math.max(0, weapon.getData().getReload().getTime()) * 50L;
        long cooldownMillis = Math.max(0L, weapon.getShootInterval());
        return weapon.getMaxCapacity() <= 1 ? reloadMillis + cooldownMillis : reloadMillis;
    }

    /** 将剩余毫秒向上换算为至少一个 tick。 */
    private static int millisToTicks(long millis) {
        return Math.max(1, (int) ((millis + 49L) / 50L));
    }

    /** 使用本体司机入口并兼容座位表尚未完成 driver 缓存解析的时刻。 */
    private static boolean isDriver(AbstractVehicle vehicle, GunnerEntity gunner) {
        if (vehicle.getDriver() == gunner) {
            return true;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == gunner.getId()) {
                return seat.seatIndex == 0;
            }
        }
        return false;
    }
}
