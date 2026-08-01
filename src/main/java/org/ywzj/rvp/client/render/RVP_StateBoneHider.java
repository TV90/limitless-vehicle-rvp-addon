package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import net.minecraft.client.Minecraft;
import org.ywzj.rvp.client.resource.vehicle.RVP_StateHiddenBone;
import org.ywzj.rvp.debug.RVP_BoneHideDebug;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.LandingGearUnit;
import org.ywzj.vehicle.vehicle.part.PartUnit;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 状态机骨骼隐藏：
 * <p>
 * 依据 display JSON 的 {@code state_hidden_bones} 配置，在载具进入指定状态并持续达到
 * 延时后，把配置的骨骼标记为不渲染（{@code BoneState.visible = false}）。
 * <p>
 * 目前支持的状态：{@code landing_gear_up}（起落架收起）。延时保证收起动画完整播放，
 * 状态退出时立即恢复渲染。
 * <p>
 * 渲染热路径（{@link #apply}）只做缓存读取与骨骼可见性设置，不触发遍历/反射；
 * 规则缓存由 {@link #rebindAll()} 在资源 reload 后一次性构建。
 */
public final class RVP_StateBoneHider {

    private static final Map<VehicleBedrockModel, List<RVP_StateHiddenBone>> MODEL_RULES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<AbstractVehicle, GearState> VEHICLE_GEAR_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 调试日志节流：仅当游戏时间跨过 60 tick 时输出一轮，避免刷屏。 */
    private static long lastDebugTick = Long.MIN_VALUE;

    /** 规则表是否已绑定；首次 apply 时若未绑定则补一次重建（资源重载挂钩漏掉时的兜底）。 */
    private static volatile boolean bound = false;

    private static final class GearState {
        boolean wasUp;
        long upSinceTick = -1;
    }

    private RVP_StateBoneHider() {}

    public static void rebindAll() {
        MODEL_RULES.clear();
        VEHICLE_GEAR_STATES.clear();
        Map<?, BaseDisplay> vehicleDisplays = ClientAssetsManager.INSTANCE.getVehicleDisplays();
        if (vehicleDisplays != null) {
            bindDisplays(vehicleDisplays.values());
        }
        bindDisplays(ClientAssetsManager.INSTANCE.getDecorationDisplays());
        bound = true;
        if (RVP_BoneHideDebug.isEnabled()) {
            RVP_BoneHideDebug.log("状态机规则重建完成：已绑定 " + MODEL_RULES.size() + " 个模型的 state_hidden_bones 规则");
        }
    }

    private static void ensureBound() {
        if (!bound) {
            rebindAll();
        }
    }

    private static void bindDisplays(Iterable<? extends BaseDisplay> displays) {
        if (displays == null) {
            return;
        }
        for (BaseDisplay display : displays) {
            if (display == null || display.getModel() == null) {
                continue;
            }
            List<RVP_StateHiddenBone> rules = resolveStateHiddenBones(display);
            if (!rules.isEmpty()) {
                if (RVP_BoneHideDebug.isEnabled()) {
                    RVP_BoneHideDebug.log("绑定状态机规则：display=" + display.getDisplayId()
                            + " model=" + display.getModel() + " rules=" + rules.size());
                }
                MODEL_RULES.put(display.getModel(), rules);
            }
        }
    }

    /** 在载具主体渲染前调用：依据当前状态设置配置骨骼的可见性。 */
    public static void apply(AbstractVehicle vehicle, BakedModelInstance instance) {
        ensureBound();
        long tick = currentTick();
        boolean dbg = RVP_BoneHideDebug.isEnabled() && debugTick(tick);
        List<RVP_StateHiddenBone> rules = rulesFor(vehicle);
        if (dbg) {
            RVP_BoneHideDebug.log("状态机 apply：vehicle=" + vehicle + " displayId=" + vehicle.getDisplayId()
                    + " instance=" + instance + " 规则数=" + rules.size());
        }
        if (rules.isEmpty()) {
            return;
        }
        Set<String> hidden = evaluate(vehicle, rules);
        if (dbg) {
            RVP_BoneHideDebug.log("状态机 evaluate：隐藏集合=" + hidden);
        }
        for (RVP_StateHiddenBone rule : rules) {
            for (String boneName : rule.bones) {
                int index = instance.getIndex(boneName);
                boolean visible = !hidden.contains(boneName);
                if (index >= 0) {
                    BoneState bone = instance.getBone(index);
                    if (bone != null) {
                        bone.visible = visible;
                    }
                }
                if (dbg) {
                    RVP_BoneHideDebug.log("状态机骨骼：bone=" + boneName + " index=" + index + " setVisible=" + visible);
                }
            }
        }
    }

    private static List<RVP_StateHiddenBone> rulesFor(AbstractVehicle vehicle) {
        VehicleBedrockModel model = ClientAssetsManager.INSTANCE
                .getVehicleDisplay(vehicle.getDisplayId())
                .map(BaseDisplay::getModel)
                .orElse(null);
        if (model == null) {
            return List.of();
        }
        return MODEL_RULES.getOrDefault(model, List.of());
    }

    private static Set<String> evaluate(AbstractVehicle vehicle, List<RVP_StateHiddenBone> rules) {
        Set<String> hidden = null;
        for (RVP_StateHiddenBone rule : rules) {
            boolean active = switch (rule.state) {
                case "landing_gear_up" -> landingGearUpActive(vehicle, rule.delayTicks);
                default -> false;
            };
            if (active) {
                if (hidden == null) {
                    hidden = new HashSet<>();
                }
                hidden.addAll(rule.bones);
            }
        }
        return hidden == null ? Set.of() : hidden;
    }

    private static boolean landingGearUpActive(AbstractVehicle vehicle, int delayTicks) {
        LandingGearUnit gear = findLandingGear(vehicle);
        if (gear == null) {
            if (isDebugTick(currentTick())) {
                RVP_BoneHideDebug.log("状态机 landing_gear_up：未找到 LandingGearUnit 部件");
            }
            return false;
        }
        GearState state = VEHICLE_GEAR_STATES.computeIfAbsent(vehicle, v -> new GearState());
        long tick = currentTick();
        boolean up = gear.isOn();
        if (isDebugTick(tick)) {
            RVP_BoneHideDebug.log("状态机 landing_gear_up：gearIsOn(收起)=" + up
                    + " wasUp=" + state.wasUp
                    + " upSinceTick=" + state.upSinceTick
                    + " tick=" + tick
                    + " 已持续=" + (state.upSinceTick < 0 ? -1 : tick - state.upSinceTick)
                    + " delayTicks=" + delayTicks);
        }
        if (up) {
            if (!state.wasUp) {
                state.wasUp = true;
                state.upSinceTick = tick;
            }
            return delayTicks <= 0 || tick - state.upSinceTick >= delayTicks;
        }
        state.wasUp = false;
        state.upSinceTick = -1;
        return false;
    }

    private static LandingGearUnit findLandingGear(AbstractVehicle vehicle) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof LandingGearUnit gear) {
                return gear;
            }
        }
        return null;
    }

    private static long currentTick() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    /** 节流：本轮是否要输出 debug 日志（每 60 tick 一次，同时推进窗口）。 */
    private static boolean debugTick(long tick) {
        // 注意：lastDebugTick 初始为 Long.MIN_VALUE，直接 tick - lastDebugTick 会溢出为负数，
        // 导致节流判断恒 false、日志永远不输出。首轮用哨兵值无条件放行。
        if (lastDebugTick == Long.MIN_VALUE || tick - lastDebugTick >= 60) {
            lastDebugTick = tick;
            return true;
        }
        return false;
    }

    /** 节流：是否恰好处于本轮 debug 窗口（与 debugTick 同一 tick，不推进窗口）。 */
    private static boolean isDebugTick(long tick) {
        return tick == lastDebugTick;
    }

    @SuppressWarnings("unchecked")
    private static List<RVP_StateHiddenBone> resolveStateHiddenBones(BaseDisplay display) {
        try {
            Method getter = display.getClass().getMethod("getStateHiddenBones");
            Object result = getter.invoke(display);
            if (result instanceof List<?> list) {
                return list.stream()
                        .filter(x -> x instanceof RVP_StateHiddenBone)
                        .map(x -> (RVP_StateHiddenBone) x)
                        .toList();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return List.of();
    }
}
