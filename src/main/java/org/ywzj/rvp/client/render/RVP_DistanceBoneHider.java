package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import net.minecraft.client.Minecraft;
import org.ywzj.rvp.client.resource.vehicle.RVP_DistanceHiddenBone;
import org.ywzj.rvp.debug.RVP_BoneHideDebug;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 距离 LOD 骨骼隐藏：
 * <p>
 * 依据 display JSON 的 {@code distance_hidden_bones} 配置，在当前玩家与载具距离超过
 * {@code distance} 时，把配置的骨骼标记为不渲染（{@code BoneState.visible = false}）；
 * 回到距离内立即恢复渲染。
 * <p>
 * 渲染热路径（{@link #apply}）只做缓存读取与骨骼可见性设置；规则缓存由
 * {@link #rebindAll()} 在资源 reload 后一次性构建。
 */
public final class RVP_DistanceBoneHider {

    private static final Map<VehicleBedrockModel, List<RVP_DistanceHiddenBone>> MODEL_RULES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 每车实例当前被距离隐藏的骨骼索引集合。
     * <p>
     * 普通骨骼的隐藏通过 {@link BoneState#visible} 生效，但特殊骨骼渲染
     * （座舱盖玻璃等半透明 {@code special_bone_effects}）由
     * {@code VehicleBedrockModel.renderSpecialBones} 强制渲染、不检查 visible，
     * 因此这里额外记录索引，供渲染循环跳过，从而把半透明材质也纳入距离 LOD。
     */
    private static final Map<BakedModelInstance, Set<Integer>> HIDDEN_BONE_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 调试日志节流：仅当游戏时间跨过 60 tick 时输出一轮，避免刷屏。 */
    private static long lastDebugTick = Long.MIN_VALUE;

    /** 规则表是否已绑定；首次 apply 时若未绑定则补一次重建（资源重载挂钩漏掉时的兜底）。 */
    private static volatile boolean bound = false;

    private RVP_DistanceBoneHider() {}

    public static void rebindAll() {
        MODEL_RULES.clear();
        Map<?, BaseDisplay> vehicleDisplays = ClientAssetsManager.INSTANCE.getVehicleDisplays();
        if (vehicleDisplays != null) {
            bindDisplays(vehicleDisplays.values());
        }
        bindDisplays(ClientAssetsManager.INSTANCE.getDecorationDisplays());
        bound = true;
        if (RVP_BoneHideDebug.isEnabled()) {
            RVP_BoneHideDebug.log("距离规则重建完成：已绑定 " + MODEL_RULES.size() + " 个模型的 distance_hidden_bones 规则");
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
            List<RVP_DistanceHiddenBone> rules = resolveDistanceHiddenBones(display);
            if (!rules.isEmpty()) {
                if (RVP_BoneHideDebug.isEnabled()) {
                    RVP_BoneHideDebug.log("绑定距离规则：display=" + display.getDisplayId()
                            + " model=" + display.getModel() + " rules=" + rules.size());
                }
                MODEL_RULES.put(display.getModel(), rules);
            }
        }
    }

    /** 在载具主体渲染前调用：依据当前玩家与载具的距离设置配置骨骼的可见性。 */
    public static void apply(AbstractVehicle vehicle, BakedModelInstance instance) {
        ensureBound();
        long tick = currentTick();
        boolean dbg = RVP_BoneHideDebug.isEnabled() && debugTick(tick);
        List<RVP_DistanceHiddenBone> rules = rulesFor(vehicle);
        if (dbg) {
            RVP_BoneHideDebug.log("距离 apply：vehicle=" + vehicle + " displayId=" + vehicle.getDisplayId()
                    + " 规则数=" + rules.size());
        }
        if (rules.isEmpty()) {
            return;
        }
        double dist = distanceToPlayer(vehicle);
        Set<String> hidden = null;
        for (RVP_DistanceHiddenBone rule : rules) {
            if (dist > rule.distance) {
                if (hidden == null) {
                    hidden = new HashSet<>();
                }
                hidden.addAll(rule.bones);
            }
        }
        if (dbg) {
            RVP_BoneHideDebug.log("距离 evaluate：dist=" + dist + " 隐藏集合=" + hidden);
        }
        Set<Integer> hiddenIndexes = null;
        for (RVP_DistanceHiddenBone rule : rules) {
            for (String boneName : rule.bones) {
                int index = instance.getIndex(boneName);
                boolean visible = hidden == null || !hidden.contains(boneName);
                if (index >= 0) {
                    BoneState bone = instance.getBone(index);
                    if (bone != null) {
                        bone.visible = visible;
                    }
                    if (!visible) {
                        if (hiddenIndexes == null) {
                            hiddenIndexes = new HashSet<>();
                        }
                        hiddenIndexes.add(index);
                    }
                }
                if (dbg) {
                    RVP_BoneHideDebug.log("距离骨骼：bone=" + boneName + " index=" + index + " setVisible=" + visible);
                }
            }
        }
        if (hiddenIndexes == null) {
            HIDDEN_BONE_INDEXES.remove(instance);
        } else {
            HIDDEN_BONE_INDEXES.put(instance, Set.copyOf(hiddenIndexes));
        }
    }

    /** 特殊骨骼渲染循环查询：该实例的指定骨骼是否被距离隐藏（从而跳过半透明渲染）。 */
    public static boolean isBoneIndexHidden(BakedModelInstance instance, int boneIndex) {
        if (instance == null || boneIndex < 0) {
            return false;
        }
        Set<Integer> indexes = HIDDEN_BONE_INDEXES.get(instance);
        return indexes != null && indexes.contains(boneIndex);
    }

    private static List<RVP_DistanceHiddenBone> rulesFor(AbstractVehicle vehicle) {
        VehicleBedrockModel model = ClientAssetsManager.INSTANCE
                .getVehicleDisplay(vehicle.getDisplayId())
                .map(BaseDisplay::getModel)
                .orElse(null);
        if (model == null) {
            return List.of();
        }
        return MODEL_RULES.getOrDefault(model, List.of());
    }

    private static double distanceToPlayer(AbstractVehicle vehicle) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0.0;
        }
        return player.position().distanceTo(vehicle.position());
    }

    private static boolean debugTick(long tick) {
        // 注意：lastDebugTick 初始为 Long.MIN_VALUE，直接 tick - lastDebugTick 会溢出为负数，
        // 导致节流判断恒 false、日志永远不输出。首轮用哨兵值无条件放行。
        if (lastDebugTick == Long.MIN_VALUE || tick - lastDebugTick >= 60) {
            lastDebugTick = tick;
            return true;
        }
        return false;
    }

    private static long currentTick() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<RVP_DistanceHiddenBone> resolveDistanceHiddenBones(BaseDisplay display) {
        try {
            Method getter = display.getClass().getMethod("getDistanceHiddenBones");
            Object result = getter.invoke(display);
            if (result instanceof List<?> list) {
                return list.stream()
                        .filter(x -> x instanceof RVP_DistanceHiddenBone)
                        .map(x -> (RVP_DistanceHiddenBone) x)
                        .toList();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return List.of();
    }
}
