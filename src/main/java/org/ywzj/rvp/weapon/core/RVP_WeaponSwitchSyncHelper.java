package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.mixin.accessor.SwitchableUnitAccessor;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 替代被删 {@code WeaponUnitSetWeaponMixin}（切换武器自动开关弹舱 + 手动覆盖）与
 * {@code WeaponUnitSwitchWeaponMixin}（切换后恢复雷达锁定）的非 mixin 实现。
 *
 * <p>挂载点：{@link RVP_WeaponBase#tick()}，每 tick 每个 RVP 武器调用一次 {@link #tick(WeaponUnit)}。
 * 内部按 unit + tick 去重，保证每个武器站每 tick 只处理一次。</p>
 *
 * <p>与原 mixin 的行为差异（已记录功能丢失表）：</p>
 * <ul>
 *   <li>自动开关弹舱通过 {@code setOnField} 直接写状态字段，绕过 {@code WeaponBayUnit.setOn}
 *       的驾驶员"弹舱：开启/关闭"提示（仅手动 toggle 弹舱时显示文案），
 *       并保留无电源不开关弹舱的语义。</li>
 *   <li>手动覆盖（玩家手动 toggle 弹舱后自动同步暂停）由原 mixin 注入的字段维护；
 *       本实现通过“索引未变但弹舱状态与期望不符”自动推断，语义一致。</li>
 *   <li>seekerOn 复位随 B3（反射卡点），不在本批次。</li>
 * </ul>
 */
public final class RVP_WeaponSwitchSyncHelper {

    private RVP_WeaponSwitchSyncHelper() {}

    /** unit -> 本 tick 已处理标记（unit 的 vehicle.tickCount）。 */
    private static final Map<WeaponUnit, Integer> LAST_TICK = new HashMap<>();
    /** unit -> 上次同步时的 [primaryIndex, secondaryIndex]。 */
    private static final Map<WeaponUnit, int[]> LAST_INDEX = new HashMap<>();
    /** unit -> 手动覆盖激活时的 [primaryIndex, secondaryIndex]（匹配时跳过自动同步）。 */
    private static final Map<WeaponUnit, int[]> MANUAL_OVERRIDE = new HashMap<>();

    /** B3：切换武器时关闭导引头的私有字段（无公共 setter，`toggleSeeker` 为 @OnlyIn(CLIENT)）。
     * 服务端不调用 toggleSeeker、seekerOn 恒为 false，无需反射；且服务端 WeaponUnit 含客户端专属字段类型
     * （{@code VehicleSound}），反射 WeaponUnit 会连带加载该类而被 RuntimeDistCleaner 拦截，
     * 每次失败后字段缓存为 null 导致每 tick 重试，造成日志刷屏与性能损耗。 */
    @Nullable
    private static volatile Field seekerOnField;

    @Nullable
    private static Field seekerOnField() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return null;
        }
        Field field = seekerOnField;
        if (field == null) {
            try {
                field = ObfuscationReflectionHelper.findField(WeaponUnit.class, "seekerOn");
            } catch (RuntimeException ignored) {
                // 反射不可用时降级（seekerOn 保持原状）
            }
            seekerOnField = field;
        }
        return field;
    }

    public static void tick(WeaponUnit unit) {
        if (unit == null || unit.getVehicle() == null) {
            return;
        }
        int tickCount = unit.getVehicle().tickCount;
        Integer lastTick = LAST_TICK.get(unit);
        if (lastTick != null && lastTick == tickCount) {
            return;
        }
        LAST_TICK.put(unit, tickCount);

        if (unit.weaponBayUnits.isEmpty()) {
            // 无弹舱的武器站仍需复位导引头（如 cssa5：HQ13 导弹切到机炮后 seekerOn 残留）
            syncSeekerOn(unit, unit.getCurrentWeaponIndex(), unit.getCurrentSecondaryWeaponIndex());
            return;
        }

        int primary = unit.getCurrentWeaponIndex();
        int secondary = unit.getCurrentSecondaryWeaponIndex();

        // 手动覆盖激活且索引未变：尊重玩家手动开关，不自动纠正
        int[] override = MANUAL_OVERRIDE.get(unit);
        if (override != null && override[0] == primary && override[1] == secondary) {
            return;
        }

        int[] lastIdx = LAST_INDEX.get(unit);
        if (lastIdx != null && lastIdx[0] == primary && lastIdx[1] == secondary) {
            // 索引未变：若玩家手动改了弹舱状态（与期望不符），标记手动覆盖
            if (override == null && isBayStateDrifted(unit, primary, secondary)) {
                MANUAL_OVERRIDE.put(unit, new int[]{primary, secondary});
            }
            return;
        }

        LAST_INDEX.put(unit, new int[]{primary, secondary});
        MANUAL_OVERRIDE.remove(unit);
        syncSeekerOn(unit, primary, secondary);
        syncBays(unit, primary, secondary);
        restoreRadarLock(unit);
    }

    /**
     * B3：替代被删 WeaponUnitSwitchWeaponMixin 的 seekerOn 复位。
     * 切换武器后，若当前主/副武器没有寻的器，自动关闭导引头（防止 SARH/IR 切到 SACLOS 时状态残留）。
     * <p>武器判定须先解开 agent / 多弹种包装（{@link RVP_WeaponResolveHelper#unwrap}）：
     * 否则 agent 包装（如 J15 的 variable_aam）的 {@code withSeeker()} 恒为 false，无弹舱载具
     * 每 tick 走本方法时会把已开启的导引头反复复位关闭。</p>
     */
    private static void syncSeekerOn(WeaponUnit unit, int primary, int secondary) {
        Field seekerField = seekerOnField();
        if (seekerField == null) {
            return;
        }
        try {
            AbstractVehicleWeapon<?> primaryWeapon = primary >= 0 && primary < unit.weapons.size()
                    ? unit.weapons.get(primary) : null;
            if (primaryWeapon != null && !hasResolvedSeeker(primaryWeapon)) {
                seekerField.setBoolean(unit, false);
            }
            AbstractVehicleWeapon<?> secondaryWeapon = secondary >= 0 && secondary < unit.secondaryWeapons.size()
                    ? unit.secondaryWeapons.get(secondary) : null;
            if (secondaryWeapon != null && !hasResolvedSeeker(secondaryWeapon)) {
                seekerField.setBoolean(unit, false);
            }
        } catch (IllegalAccessException e) {
            // 反射失败：seekerOn 保持原状（极罕见，字段访问已 setAccessible）
        }
    }

    /** 解开 agent / 多弹种包装后判断是否带寻的器。 */
    private static boolean hasResolvedSeeker(AbstractVehicleWeapon<?> weapon) {
        AbstractVehicleWeapon<?> resolved = RVP_WeaponResolveHelper.unwrap(weapon);
        return resolved != null && resolved.withSeeker();
    }

    private static void syncBays(WeaponUnit unit, int primary, int secondary) {
        WeaponBayUnit target = resolveTargetBay(unit, primary, secondary);
        for (WeaponBayUnit bay : unit.weaponBayUnits.values()) {
            boolean shouldBeOn = (bay == target);
            if (bay.isOn() != shouldBeOn) {
                // 自动开关弹舱直接写状态字段，绕过本体 WeaponBayUnit.setOn 的
                // "弹舱：开启/关闭"驾驶员提示（仅手动 toggle 弹舱时显示文案）；
                // 同时保留无电源不开关弹舱的语义（等价于本体 setOn 的 hasPower 检查）。
                if (!bay.getVehicle().hasPower()) {
                    continue;
                }
                ((SwitchableUnitAccessor) bay).setOnField(shouldBeOn);
            }
        }
    }

    private static boolean isBayStateDrifted(WeaponUnit unit, int primary, int secondary) {
        WeaponBayUnit target = resolveTargetBay(unit, primary, secondary);
        for (WeaponBayUnit bay : unit.weaponBayUnits.values()) {
            if (bay.isOn() != (bay == target)) {
                return true;
            }
        }
        return false;
    }

    private static WeaponBayUnit resolveTargetBay(WeaponUnit unit, int primary, int secondary) {
        WeaponBayUnit target = null;
        if (primary >= 0 && primary < unit.weapons.size()) {
            target = unit.weaponBayUnits.get(unit.weapons.get(primary));
        }
        if (target == null && secondary >= 0 && secondary < unit.secondaryWeapons.size()) {
            target = unit.weaponBayUnits.get(unit.secondaryWeapons.get(secondary));
        }
        return target;
    }

    private static void restoreRadarLock(WeaponUnit unit) {
        WeaponUnit root = unit.getRootParentWeaponUnit();
        if (root == null) {
            return;
        }
        if (root.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.RF) {
            return;
        }
        RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(root);
        if (radar == null) {
            return;
        }
        Entity radarLocked = radar.getLockedEntity();
        if (radarLocked != null && radarLocked.isAlive()) {
            root.setLockedEntity(radarLocked);
        }
    }
}
