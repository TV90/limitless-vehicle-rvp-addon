package org.ywzj.rvp.weapon.damage;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一次爆炸的真实直击 OBB 身份；嵌套爆炸独立保存/恢复，不借用伤害来源中的射手位置。 */
public final class RVP_ExplosionHitboxContext {
    /** @param vehicle 被直击载具 UUID @param key 被直击 OBB @param tick 碰撞发生的游戏 tick */
    public record Impact(UUID vehicle, RVP_NamedVehicleObbs.Key key, long tick) {}
    /** 只存在于当前线程本次同步爆炸调用中，离开窗口立即恢复外层上下文。 */
    private static final ThreadLocal<Impact> CURRENT = new ThreadLocal<>();
    private RVP_ExplosionHitboxContext() {}

    @Nullable public static Impact current() { return CURRENT.get(); }

    public static void run(@Nullable Impact impact, Runnable action) {
        Impact previous = CURRENT.get();
        if (impact == null) CURRENT.remove(); else CURRENT.set(impact);
        try { action.run(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** 直击仅对本次被击中的载具生效；其它波及车辆及无直接碰撞的近炸按全部 OBB 的最近表面选取。 */
    public static float factor(UUID vehicle, List<RVP_NamedVehicleObbs.Box> boxes,
                               Map<String, Float> factors, Vec3 center, @Nullable Impact impact) {
        if (factors.isEmpty()) return 1F;
        RVP_NamedVehicleObbs.Key key = impact != null && impact.vehicle.equals(vehicle) ? impact.key : null;
        if (key == null) {
            var nearest = RVP_NamedVehicleObbs.nearest(boxes, center);
            key = nearest == null ? null : nearest.key();
        }
        float value = key == null ? 1F : factors.getOrDefault(key.bone(), 1F);
        return Float.isFinite(value) && value >= 0 ? value : 1F;
    }
}
