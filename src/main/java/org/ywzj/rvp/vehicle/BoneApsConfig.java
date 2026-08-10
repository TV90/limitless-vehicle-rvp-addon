package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 骨块级主动防护（APS）传感器模块配置（{@code bone_modules} 骨块条目的 {@code aps} 子对象）。
 *
 * <p>{@code bone_modules} 条目挂在<b>传感器骨块</b>名下（如 {@code aps_sensor_right}），该骨块是
 * 被命中打掉后的失效点；{@link #launcherPart} 引用<b>发射器武器部件</b>（如 {@code aps_right}），
 * 仅作为拦截弹火焰起始点与开火动画锚点，<b>不参与失效判定</b>（发射器骨骼被打掉不影响 APS）。</p>
 *
 * <p>每个传感器模块拥有独立的探测扇区（{@link #facingPart} + {@link #facingYawDeg} + {@link #scanFovDeg}）：
 * 右侧模块只拦截右侧来袭的弹药、左侧模块只拦截左侧来袭的弹药。传感器骨块被打掉（APS 模块失效）后，
 * 该侧扇区失去拦截能力，另一侧仍正常工作。</p>
 *
 * @param launcherPart        发射器武器部件 id（如 {@code "aps_right"}），必填；缺省视为未启用
 * @param facingPart          探测扇区朝向跟随的部件 id（如 {@code "turret"}），随部件旋转；
 *                            为 null 或空时跟随车体朝向
 * @param facingYawDeg        扇区朝向水平偏置角（度，正值向左）。
 *                            右侧模块 -90、左侧模块 +90 各覆盖左右扇区
 * @param scanFovDeg          探测扇区全角（度），半角 = fov/2 用于 withinAngle 判定（默认 120）
 * @param detectRadius        探测距离（格）（默认 32）
 * @param interceptRadius     拦截球半径（格），目标位置周围（默认 8）
 * @param ammoMax             弹药上限；0 = 模块未启用（默认 0）
 * @param reloadOneTick       每发装填所需 tick（默认 600）
 * @param cooldownTick        拦截后冷却 tick（默认 20）
 * @param interceptDelayTick  锁定到拦截的延迟 tick，0 = 立即（默认 0）
 * @param scanIntervalTick    扫描节流间隔 tick（默认 10）
 * @param projectileSpeedMin  可拦截弹体速度下限（默认 1.0）
 * @param projectileSpeedMax  可拦截弹体速度上限（默认 80.0）
 * @param excludeOwnerProjectile 排除本车发射的弹体（默认 true）
 */
public record BoneApsConfig(
        @Nullable String launcherPart,
        @Nullable String facingPart,
        double facingYawDeg,
        double scanFovDeg,
        double detectRadius,
        double interceptRadius,
        int ammoMax,
        int reloadOneTick,
        int cooldownTick,
        int interceptDelayTick,
        int scanIntervalTick,
        double projectileSpeedMin,
        double projectileSpeedMax,
        boolean excludeOwnerProjectile
) {
    /** 半角（度）。 */
    public double halfAngleDeg() {
        return Math.max(0.0, scanFovDeg) / 2.0;
    }

    /** 模块是否启用：发射器部件与弹药上限均须有效。 */
    public boolean isEnabled() {
        return launcherPart != null && !launcherPart.isBlank() && ammoMax > 0;
    }

    public static @Nullable BoneApsConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String launcherPart = GsonHelper.getAsString(obj, "launcher_part", null);
        if (launcherPart != null && launcherPart.isBlank()) {
            launcherPart = null;
        }
        String facingPart = GsonHelper.getAsString(obj, "facing_part", null);
        if (facingPart != null && facingPart.isBlank()) {
            facingPart = null;
        }
        double facingYawDeg = GsonHelper.getAsDouble(obj, "facing_yaw", 0.0);
        double scanFovDeg = GsonHelper.getAsDouble(obj, "scan_fov", 120.0);
        double detectRadius = GsonHelper.getAsDouble(obj, "detect_radius", 32.0);
        double interceptRadius = GsonHelper.getAsDouble(obj, "intercept_radius", 8.0);
        int ammoMax = Math.max(0, GsonHelper.getAsInt(obj, "ammo_max", 0));
        int reloadOneTick = Math.max(1, GsonHelper.getAsInt(obj, "reload_one_tick", 600));
        int cooldownTick = Math.max(1, GsonHelper.getAsInt(obj, "cooldown_tick", 20));
        int interceptDelayTick = Math.max(0, GsonHelper.getAsInt(obj, "intercept_delay_tick", 0));
        int scanIntervalTick = Math.max(1, GsonHelper.getAsInt(obj, "scan_interval_tick", 10));
        double speedMin = Math.max(0.0, GsonHelper.getAsDouble(obj, "projectile_speed_min", 1.0));
        double speedMax = GsonHelper.getAsDouble(obj, "projectile_speed_max", 80.0);
        if (speedMax < speedMin) {
            speedMax = speedMin;
        }
        boolean excludeOwnerProjectile = GsonHelper.getAsBoolean(obj, "exclude_owner_projectile", true);
        return new BoneApsConfig(launcherPart, facingPart, facingYawDeg, Math.max(0.0, scanFovDeg),
                Math.max(0.0, detectRadius), Math.max(0.1, interceptRadius), ammoMax,
                reloadOneTick, cooldownTick, interceptDelayTick, scanIntervalTick,
                speedMin, speedMax, excludeOwnerProjectile);
    }
}
