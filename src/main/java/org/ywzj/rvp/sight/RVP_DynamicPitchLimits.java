package org.ywzj.rvp.sight;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_RearDepressionBlockConfig;
import org.ywzj.rvp.config.RVP_SightFireDisguiseConfig;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 武器站俯角上限动态控制，单点写回 {@code x_rot_max}。
 *
 * <p><b>本体坐标约定：{@code xRot} 负值 = 仰角（炮管向上），正值 = 俯角（炮管向下）；
 * {@code x_rot_min} 是仰角上限，{@code x_rot_max} 是俯角上限。</b>本类只动俯角上限，
 * 不碰仰角。</p>
 *
 * <ul>
 *   <li>摄像机射弹俯角补偿：观瞄（SCOPE）期间把本站 {@code x_rot_max} 增加配置角度
 *       （正数 = 额外下压）；</li>
 *   <li>车尾分段无俯角：炮塔局部偏航落在车尾轴线 ±half_angle 内时把 {@code x_rot_max}
 *       压到 0（炮管最低只能水平），再往外 blend_deg 的余量段线性过渡回原俯角上限
 *       （避让高耸的发动机舱）。</li>
 * </ul>
 *
 * <p>两个效果共用同一张原始 {@code x_rot_max} 基准表，避免互相把对方改过的值当基准；
 * 每 tick 从基准重算而非累乘。服务端在 LevelTick START 扫描已加载载具（先于 tickParts），
 * 客户端在 ClientTickEvent START 做同样的事，保证鼠标输入门限、AI 瞄准与远端渲染一致。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_DynamicPitchLimits {

    /** 车尾轴线在炮塔局部偏航中的角度。 */
    private static final float REAR_CENTER_YAW_DEG = 180.0f;

    private static final Map<WeaponUnit, Float> SERVER_BASE_MAX = new WeakHashMap<>();
    private static final Set<WeaponUnit> SERVER_APPLIED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<WeaponUnit, Float> CLIENT_BASE_MAX = new WeakHashMap<>();
    private static final Set<WeaponUnit> CLIENT_APPLIED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private RVP_DynamicPitchLimits() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.level instanceof ServerLevel level)) {
            return;
        }
        Set<WeaponUnit> active = applyVehicles(level.getEntities().getAll(), scopedUnits(level), false);
        restoreAllExcept(active, false);
    }

    /** 客户端入口：本地观瞄站 + 所有已加载载具的车尾限位。 */
    public static void tickClient(Iterable<Entity> entities, WeaponUnit scopedUnit) {
        Set<WeaponUnit> scoped = scopedUnit == null ? Set.of() : Set.of(scopedUnit);
        Set<WeaponUnit> active = applyVehicles(entities, scoped, true);
        restoreAllExcept(active, true);
    }

    private static Set<WeaponUnit> applyVehicles(Iterable<Entity> entities, Set<WeaponUnit> scoped,
                                                 boolean clientSide) {
        Set<WeaponUnit> active = new HashSet<>();
        if (entities == null) {
            return active;
        }
        for (Entity entity : entities) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (!(part instanceof WeaponUnit unit)) {
                    continue;
                }
                boolean camera = scoped.contains(unit);
                RVP_RearDepressionBlockConfig rear = rearConfig(unit);
                if (!camera && rear == null) {
                    continue;
                }
                apply(unit, camera, rear, clientSide);
                active.add(unit);
            }
        }
        return active;
    }

    private static void apply(WeaponUnit unit, boolean cameraActive,
                              RVP_RearDepressionBlockConfig rear, boolean clientSide) {
        // 数值上限 = 俯角上限；baseMax 是 JSON 原值（M1 系列 +10 = 俯 10°）
        float baseMax = maxBases(clientSide).computeIfAbsent(unit, WeaponUnit::getXRotMax);
        // 摄像机补偿正数 = 额外下压 = 放大俯角上限
        float limit = baseMax + cameraCompensation(unit, cameraActive);
        if (rear != null) {
            float offAxis = Math.abs(Mth.wrapDegrees(unit.getYRot() - REAR_CENTER_YAW_DEG));
            float half = rear.halfAngleDeg();
            float blend = rear.blendDeg();
            float outer = blend <= 0.0f
                    ? (offAxis <= half ? 0.0f : 1.0f)
                    : Mth.clamp((offAxis - half) / blend, 0.0f, 1.0f);
            // 后弧核心内俯角上限压到 0（最低水平）；原版就不允许下压（baseMax<=0）时保持原值
            float blocked = Math.min(baseMax, 0.0f);
            limit = Mth.lerp(outer, blocked, limit);
        }
        unit.setXRotMax(limit);
        if (clientSide && cameraActive) {
            // 观瞄时意图角跟随新俯角上限，避免镜头已被限位抬起、瞄准角却还停在下方，
            // 导致鼠标俯仰变钝；只改本地客户端，服务端由上行 xAimRot 跟随，避免伪造观瞄包
            unit.setXAimRot(Mth.clamp(unit.getXAimRot(),
                    unit.getXRotMin() - unit.xSelfRot, limit - unit.xSelfRot));
        }
    }

    private static float cameraCompensation(WeaponUnit unit, boolean cameraActive) {
        if (!cameraActive) {
            return 0.0f;
        }
        RVP_SightFireDisguiseConfig config = sightConfig(unit);
        return config == null ? 0.0f : config.cameraDepressionCompensationDeg();
    }

    /** 恢复所有不在 active 内的站，覆盖载具卸载、配置移除与退出观瞄。 */
    private static void restoreAllExcept(Set<WeaponUnit> active, boolean clientSide) {
        Set<WeaponUnit> applied = appliedSet(clientSide);
        Map<WeaponUnit, Float> bases = maxBases(clientSide);
        applied.removeIf(unit -> {
            if (active.contains(unit)) {
                return false;
            }
            unit.setXRotMax(bases.getOrDefault(unit, unit.getXRotMax()));
            bases.remove(unit);
            return true;
        });
        applied.addAll(active);
    }

    private static Set<WeaponUnit> scopedUnits(ServerLevel level) {
        Set<WeaponUnit> units = new HashSet<>();
        long gameTime = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            RVP_ScopeViewStateTable.ScopeState state = RVP_ScopeViewStateTable.get(player, gameTime);
            if (state == null) {
                continue;
            }
            Entity entity = level.getEntity(state.vehicleId());
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            int index = state.partUnitIndex();
            if (index < 0 || index >= vehicle.getPartUnits().size()) {
                continue;
            }
            if (vehicle.getPartUnits().get(index) instanceof WeaponUnit unit) {
                units.add(unit);
            }
        }
        return units;
    }

    private static RVP_RearDepressionBlockConfig rearConfig(WeaponUnit unit) {
        return unit.getData() instanceof WeaponUnitDataExt ext
                ? ext.ywzj_rvp$getRearDepressionBlock()
                : null;
    }

    private static RVP_SightFireDisguiseConfig sightConfig(WeaponUnit unit) {
        return unit.getData() instanceof WeaponUnitDataExt ext
                ? ext.ywzj_rvp$getSightFireDisguise()
                : null;
    }

    private static Map<WeaponUnit, Float> maxBases(boolean clientSide) {
        return clientSide ? CLIENT_BASE_MAX : SERVER_BASE_MAX;
    }

    private static Set<WeaponUnit> appliedSet(boolean clientSide) {
        return clientSide ? CLIENT_APPLIED : SERVER_APPLIED;
    }
}
