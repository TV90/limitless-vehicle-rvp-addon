package org.ywzj.rvp.weapon.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * [RVP] IR 导引头 armed 授权表——服务端（2026-10-21「连续跟踪锁定」）。
 *
 * <p>key = 根武器站 + weaponId；value = {armed 目标实体 id, 授权 game time}。
 * 写入者两处：① {@code C2SIrSeekerArmed}（玩家客户端跃迁上报，收包复算包线后写）；
 * ② {@code RVP_ServerIrSeekerTracker}（AI 炮手服务端自算 dwell 后写，方案 D3）。
 * 消费方：{@code RVP_ProjectileWeapon.dispatchShots} 对 IR 弹只取本表目标，表空即拒射。</p>
 *
 * <p>服务端不自己扫玩家目标，只「校验 + 复算包线」；表项不主动过期——读取时按
 * 目标存活实时判定，目标死亡即视为未授权。</p>
 */
public final class RVP_ServerIrSeekerArmedTable {

    private RVP_ServerIrSeekerArmedTable() {}

    /** 单条授权：armed 目标实体 id + 授权时刻。 */
    public record ArmedEntry(int entityId, long armedGameTime) {}

    /**
     * 根站 -> 弹种 -> 授权。弱键：ArmedEntry 不引用 WeaponUnit，载具卸载后允许回收；
     * 停服/世界卸载时另有 {@link #clearAll()} 立即清空。
     */
    private static final Map<WeaponUnit, Map<ResourceLocation, ArmedEntry>> TABLE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 写入/刷新授权（AI 与收包校验通过后调用）。 */
    public static void arm(WeaponUnit root, ResourceLocation weaponId, int entityId, long gameTime) {
        if (root == null || weaponId == null) {
            return;
        }
        TABLE.computeIfAbsent(root, u -> new HashMap<>())
                .put(weaponId, new ArmedEntry(entityId, gameTime));
    }

    /** 解除授权（客户端解锁包 / AI 目标丢失）。 */
    public static void disarm(WeaponUnit root, ResourceLocation weaponId) {
        if (root == null || weaponId == null) {
            return;
        }
        Map<ResourceLocation, ArmedEntry> byWeapon = TABLE.get(root);
        if (byWeapon == null) {
            return;
        }
        byWeapon.remove(weaponId);
        // 子键清空后连同根站条目一起移除，避免 root 永久留在静态表里。
        if (byWeapon.isEmpty()) {
            TABLE.remove(root);
        }
    }

    /** 世界卸载/停服时清空全部 IR 授权。 */
    public static void clearAll() {
        TABLE.clear();
    }

    /** @return armed 目标实体（实时解析 + 存活校验）；未授权/已失效返回 null。 */
    @Nullable
    public static Entity getArmedEntity(WeaponUnit root, @Nullable ResourceLocation weaponId) {
        ArmedEntry entry = getEntry(root, weaponId);
        if (entry == null || root.getVehicle() == null) {
            return null;
        }
        Entity target = root.getVehicle().level().getEntity(entry.entityId());
        return target != null && target.isAlive() ? target : null;
    }

    /** @return 是否存在有效授权（含目标存活校验）。 */
    public static boolean isArmed(WeaponUnit root, @Nullable ResourceLocation weaponId) {
        return getArmedEntity(root, weaponId) != null;
    }

    /** 清空指定站的授权（载具移除/站失效时调用；读取侧对失效项天然拒射，本钩子为防泄漏冗余）。 */
    public static void clearUnit(WeaponUnit root) {
        TABLE.remove(root);
    }

    @Nullable
    private static ArmedEntry getEntry(WeaponUnit root, @Nullable ResourceLocation weaponId) {
        if (root == null || weaponId == null) {
            return null;
        }
        return TABLE.getOrDefault(root, Map.of()).get(weaponId);
    }
}
