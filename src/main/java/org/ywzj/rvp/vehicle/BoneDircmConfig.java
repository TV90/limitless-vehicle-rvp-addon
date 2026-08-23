package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 骨块级定向红外对抗（DIRCM）照射模块配置（{@code bone_modules} 骨块条目的 {@code dircm} 子对象）。
 *
 * <p>{@code bone_modules} 条目挂在<b>照射骨骼</b>名下（如 {@code dircm_l} / {@code dircm_r}），
 * 该骨块是被命中打掉后的失效点；{@link #laserPart} 引用<b>激光照射武器部件</b>
 * （{@code ywzj_vehicle:weapon} 型，如 {@code dircm_l}），既作为光束起点，也可被动画脚本经
 * {@code getPartXRot/getPartYRot} 联动旋转（发射器随动指向来袭方向）。</p>
 *
 * <p>每个模块拥有独立的探测扇区（{@link #facingPart} + {@link #facingYawDeg} + {@link #scanFovDeg}）
 * 与一个火力通道：一个照射骨骼同时只能照射一个目标；载具可配多个模块获得多通道
 * （如左右各一，配合 {@code facing_yaw ±90} 覆盖两侧扇区）。</p>
 *
 * <p>干扰语义：光束建立瞬间即完成干扰判定——目标当前有效制导类型属于
 * IR/AIR/ARH/HITL_TV/HITL_CLOS_TV 时立即「丢制导 + 强制偏转」；其余弹体（含无制导火箭、
 * 普通炸弹）仅占通道不干扰。干扰效果为弹体级持久状态（HITL 弹 3 秒后恢复）。</p>
 *
 * @param laserPart         激光照射武器部件 id（如 {@code "dircm_l"}），必填；缺省视为未启用
 * @param facingPart        探测扇区朝向跟随的部件 id（如 {@code "sighting_system"}），随部件旋转；
 *                          为 null 或空时跟随车体朝向
 * @param facingYawDeg      扇区朝向水平偏置角（度）。正值朝车头右侧（-X），负值朝左侧（+X）；
 *                          左模块 -90、右模块 +90 各覆盖两侧
 * @param scanFovDeg        探测扇区全角（度），半角 = fov/2 用于 withinAngle 判定（默认 120）
 * @param detectRadius      探测距离（格）（默认 2000）
 * @param approachAngleDeg  来袭角判定（度，半角）：只干扰朝本车飞来的弹（默认 60）
 * @param beamTick          激光光束美术特效持续时长（tick）（默认 20 = 1 秒）；
 *                          干扰在光束建立瞬间即生效，本字段只控制特效跟踪时长
 * @param chargeTick        一次照射后充能时长（tick）（默认 300 = 15 秒）
 * @param scanIntervalTick  扫描节流间隔 tick（默认 5）
 * @param excludeOwnerProjectile 排除本车发射的弹体（默认 true）
 */
public record BoneDircmConfig(
        @Nullable String laserPart,
        @Nullable String facingPart,
        double facingYawDeg,
        double scanFovDeg,
        double detectRadius,
        double approachAngleDeg,
        int beamTick,
        int chargeTick,
        int scanIntervalTick,
        boolean excludeOwnerProjectile
) {
    /** 半角（度）。 */
    public double halfAngleDeg() {
        return Math.max(0.0, scanFovDeg) / 2.0;
    }

    /** 来袭角判定半角（度），0 = 必须完全正对。 */
    public double approachHalfAngleDeg() {
        return Math.max(0.0, approachAngleDeg);
    }

    /** 模块是否启用：照射部件有效。 */
    public boolean isEnabled() {
        return laserPart != null && !laserPart.isBlank();
    }

    public static @Nullable BoneDircmConfig parse(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String laserPart = GsonHelper.getAsString(obj, "laser_part", null);
        if (laserPart != null && laserPart.isBlank()) {
            laserPart = null;
        }
        String facingPart = GsonHelper.getAsString(obj, "facing_part", null);
        if (facingPart != null && facingPart.isBlank()) {
            facingPart = null;
        }
        double facingYawDeg = GsonHelper.getAsDouble(obj, "facing_yaw", 0.0);
        double scanFovDeg = GsonHelper.getAsDouble(obj, "scan_fov", 120.0);
        double detectRadius = GsonHelper.getAsDouble(obj, "detect_radius", 2000.0);
        double approachAngleDeg = GsonHelper.getAsDouble(obj, "approach_angle", 60.0);
        int beamTick = Math.max(1, GsonHelper.getAsInt(obj, "beam_tick", 20));
        int chargeTick = Math.max(1, GsonHelper.getAsInt(obj, "charge_tick", 300));
        int scanIntervalTick = Math.max(1, GsonHelper.getAsInt(obj, "scan_interval_tick", 5));
        boolean excludeOwnerProjectile = GsonHelper.getAsBoolean(obj, "exclude_owner_projectile", true);
        return new BoneDircmConfig(laserPart, facingPart, facingYawDeg,
                Math.max(0.0, scanFovDeg), Math.max(0.0, detectRadius),
                Math.max(0.0, approachAngleDeg), beamTick, chargeTick,
                scanIntervalTick, excludeOwnerProjectile);
    }
}