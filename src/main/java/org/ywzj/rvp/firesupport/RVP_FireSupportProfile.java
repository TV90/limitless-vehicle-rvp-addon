package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一份已通过严格校验的炮火支援配置。所有集合均为不可变副本，任务可安全冻结并跨重载持有。
 */
public record RVP_FireSupportProfile(
        /** 当前配置格式版本；阶段 A 只接受 1。 */ int schemaVersion,
        /** UI 使用的翻译键。 */ String translationKey,
        /** 终端持有规则。 */ HolderPolicy holderPolicy,
        /** 呼叫阶段规则。 */ CallStage callStage,
        /** 打击阶段规则。 */ StrikeStage strikeStage,
        /** 服务端安全与性能上限。 */ Limits limits,
        /** 以 profile 内弹种 ID 为键的不可变弹种表。 */ Map<String, Munition> munitions,
        /** 以 profile 内模式 ID 为键的不可变射击模式表。 */ Map<String, FireMode> fireModes,
        /** 以 profile 内预设 ID 为键的不可变打击预设表。 */ Map<String, PatternPreset> patterns) {

    public RVP_FireSupportProfile {
        munitions = Map.copyOf(munitions);
        fireModes = Map.copyOf(fireModes);
        patterns = Map.copyOf(patterns);
    }

    /** 打开、提交和停火时需要匹配的物品与手。 */
    public record HolderPolicy(
            /** 必须持有的物品注册 ID。 */ ResourceLocation requiredItem,
            /** 允许的手，当前值仅为 main/off。 */ Set<String> allowedHands) {
        public HolderPolicy { allowedHands = Set.copyOf(allowedHands); }
    }

    /** 呼叫完成前的生命周期配置。 */
    public record CallStage(
            /** 模式倍率应用前的基础时长，单位 Tick，默认 800。 */ int baseDurationTicks,
            /** 玩家死亡时是否取消；首版固定为 true。 */ boolean cancelOnPlayerDeath,
            /** 绑定终端离开物品栏时是否取消；首版固定为 true。 */ boolean cancelOnTerminalLost,
            /** 玩家断线时是否取消，默认 true。 */ boolean cancelOnDisconnect) {}

    /** 进入打击阶段后的生命周期配置。 */
    public record StrikeStage(
            /** 停火命令从接受到生效的延迟，单位 Tick，默认 80。 */ int ceaseFireDelayTicks) {}

    /** 可配置上限；解析器还会施加不可配置的绝对保险上限。 */
    public record Limits(
            /** 目标 XZ 最小水平距离，单位格。 */ double minTargetDistanceMeters,
            /** 目标 XZ 最大水平距离，单位格。 */ double maxTargetDistanceMeters,
            /** 单任务最大逻辑弹数。 */ int maxRoundsPerMission,
            /** 单玩家最大并行任务数。 */ int maxActiveMissionsPerPlayer,
            /** 全服最大并行任务数。 */ int maxActiveMissionsGlobal,
            /** 成功接受后的请求冷却，单位 Tick。 */ int requestCooldownTicks,
            /** 从接受到最后计划弹的最长时间，单位 Tick。 */ int maxMissionDurationTicks,
            /** 单任务最多租约的唯一区块数。 */ int maxLoadedChunksPerMission,
            /** 请求允许携带的动态参数键数量。 */ int maxParameterCount) {}

    /** 一种可选弹种及其投送定义。 */
    public record Munition(
            /** profile 内的小写选择 ID。 */ String id,
            /** UI 使用的翻译键。 */ String translationKey,
            /** 引用的真实 RVP 武器资源 ID。 */ ResourceLocation weaponId,
            /** 一基数包含的顶层逻辑弹体数量。 */ int roundsPerUnit,
            /** 投送工厂类型 ID。 */ ResourceLocation deliveryType,
            /** 已由投送工厂严格解析的不可变配置对象。 */ Object deliveryData) {}

    /** 数据驱动射击模式；调度器不按 ID 分支。 */
    public record FireMode(
            /** profile 内的小写选择 ID。 */ String id,
            /** UI 使用的翻译键。 */ String translationKey,
            /** 呼叫阶段时长倍率，必须为有限正数。 */ double callDurationMultiplier,
            /** 所有几何尺寸的散布倍率，必须为有限正数。 */ double dispersionMultiplier,
            /** 按声明顺序执行的不可变阶段列表。 */ List<Phase> phases) {
        public FireMode { phases = List.copyOf(phases); }
    }

    /** 一个通用计时阶段。 */
    public record Phase(
            /** 模式内唯一的小写 ID。 */ String id,
            /** UI 使用的翻译键。 */ String translationKey,
            /** 相对打击开始或上一阶段末弹的延迟，单位 Tick。 */ int startDelayTicks,
            /** 已校验的弹数规则。 */ RoundRule rounds,
            /** 固定相邻弹间隔；与 durationTicks 仅一个非空。 */ Integer intervalTicks,
            /** 首末弹时间跨度；与 intervalTicks 仅一个非空。 */ Integer durationTicks) {}

    /** 弹数规则；只允许基数倍率、随机闭区间或固定弹数三者之一。 */
    public record RoundRule(
            /** 与弹种基数相乘后向上取整；不用时为 null。 */ Double baseMultiplier,
            /** 权威随机闭区间下界；不用时为 null。 */ Integer randomMin,
            /** 权威随机闭区间上界；不用时为 null。 */ Integer randomMax,
            /** 固定弹数；不用时为 null。 */ Integer fixed) {}

    /** 一个打击预设及其类型化几何实现。 */
    public record PatternPreset(
            /** profile 内的小写选择 ID。 */ String id,
            /** UI 使用的翻译键。 */ String translationKey,
            /** 几何工厂类型 ID。 */ ResourceLocation type,
            /** 可由 UI 调整的不可变参数规格表。 */ Map<String, ParameterSpec> parameters,
            /** 工厂创建的不可变纯数学实现。 */ RVP_FireSupportPattern pattern) {
        public PatternPreset { parameters = Map.copyOf(parameters); }
    }

    /** 一个 double 动态参数的边界和步长。 */
    public record ParameterSpec(
            /** 默认值，单位由 unit 描述。 */ double defaultValue,
            /** 允许的最小值。 */ double min,
            /** 允许的最大值。 */ double max,
            /** UI 调整数值的正步长。 */ double step,
            /** 仅用于显示的单位字符串。 */ String unit) {}
}
