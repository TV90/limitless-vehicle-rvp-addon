package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.Entity;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 焦点吸附锁定的跨端登记表。
 *
 * <p>客户端建立/解除；公共侧 {@code getFireControlSensorType()} 覆写读取，
 * 让本体实体锁定分支按 EO 口径校验吸附目标，避免传感器为 NONE/IR/RF 的
 * 观瞄站一建立吸附就被本体实体验证清掉。</p>
 *
 * <p>只保存武器站弱键与实体 ID，不引用任何客户端类型，双端可加载。</p>
 */
public final class RVP_FocusLockSnapState {

    /** 武器站 → 吸附目标实体 ID；弱键，载具卸载后自动回收。 */
    private static final Map<WeaponUnit, Integer> SNAPPED = Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_FocusLockSnapState() {
    }

    /** 记录一次吸附锁定。 */
    public static void mark(WeaponUnit unit, Entity target) {
        if (unit != null && target != null) {
            SNAPPED.put(unit, target.getId());
        }
    }

    /** 解除吸附登记。 */
    public static void clear(WeaponUnit unit) {
        if (unit != null) {
            SNAPPED.remove(unit);
        }
    }

    /** 当前武器站是否持有吸附锁定。 */
    public static boolean isSnapped(WeaponUnit unit) {
        return unit != null && SNAPPED.containsKey(unit);
    }

    /** 吸附目标实体 ID，未登记返回 -1。 */
    public static int targetId(WeaponUnit unit) {
        Integer id = unit == null ? null : SNAPPED.get(unit);
        return id == null ? -1 : id;
    }

    /** 当前全部登记站的快照，供逐刻校验遍历。 */
    public static List<WeaponUnit> stations() {
        synchronized (SNAPPED) {
            return new ArrayList<>(SNAPPED.keySet());
        }
    }
}
