package org.ywzj.rvp.client.tuning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.debug.RVP_ShockwaveTuningCommands;
import org.ywzj.rvp.client.light.RVP_ScreenLightDebugCommands;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_HaloDecayCurve;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/** 首批屏幕空间特效适配器；只有本类知道光照、喷焰和冲击波的业务枚举。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_ScreenSpaceTuningModule implements RVP_TuningModule {
    /** 初始化后不再改变的六组参数，界面只负责按描述生成控件。 */
    private final List<Group> groups;

    public RVP_ScreenSpaceTuningModule() {
        List<Group> result = new ArrayList<>();
        for (RVP_LightRuntimeTuning.Group group : RVP_LightRuntimeTuning.Group.values()) {
            List<RVP_TuningOption> options = new ArrayList<>();
            for (Parameter parameter : Parameter.values()) {
                // 调用既有参数适用性，避免为没有接入渲染的组合生成无效控件。
                if (parameter.supports(group)) { options.add(lightOption(group, parameter)); }
            }
            String title = switch (group) {
                case global -> "环境光与光晕总设置";
                case explosion -> "爆炸照亮、光晕与天空";
                case muzzle -> "炮口照亮与光晕";
                case engine -> "发动机喷焰与照亮";
                case bloom -> "多层柔化光晕";
            };
            String description = "仅影响本机画面，修改下一帧生效；不改变伤害或原版方块光照。"
                    + (group == RVP_LightRuntimeTuning.Group.engine
                    ? "发动机照亮与外晕默认关闭；白热喷焰独立保留。" : "")
                    + (group == RVP_LightRuntimeTuning.Group.bloom
                    ? "光晕是亮物体周围的柔和扩散，仅处理RVP发光内容。" : "");
            List<Action> actions = group == RVP_LightRuntimeTuning.Group.muzzle ? List.of()
                    : List.of(new Action("爆炸光预览", "在准星附近生成无伤害短光；需要进入世界。", () -> lightPreview(false), true),
                    new Action("喷焰预览", "显示两秒的发动机喷焰；不发射弹体，不造成伤害。", () -> lightPreview(true), true));
            result.add(new Group(group.name(), title, description, options, actions));
        }
        List<RVP_TuningOption> shockwave = new ArrayList<>();
        var sphere = RVP_ShockwaveRuntimeTuning.Group.sphere;
        for (var parameter : RVP_ShockwaveRuntimeTuning.Parameter.values()) {
            if (!parameter.supports(sphere)) { continue; }
            String unit = switch (parameter) {
                case expansionTicks, lifetime, fadeInTicks, fadeStartTicks -> "刻（20刻=1秒）";
                case initialRadius -> "格";
                default -> "系数";
            };
            // 调用原有会话覆盖和单项清理，正在播放的冲击波仍保持创建时的快照。
            shockwave.add(new RVP_TuningOption(parameter.name(), parameter.description, parameter.description
                    + "；仅影响下一次爆炸或预览，已播放的效果保持原参数。", unit, RVP_TuningOption.Control.NUMBER,
                    decimal(parameter.min), decimal(parameter.max), 0,
                    () -> decimal(parameter.defaultValue(sphere)), () -> RVP_ShockwaveRuntimeTuning.override(sphere, parameter),
                    value -> RVP_ShockwaveRuntimeTuning.set(sphere, parameter, (float) value),
                    () -> RVP_ShockwaveRuntimeTuning.reset(sphere, parameter)));
        }
        result.add(new Group("shockwave_sphere", "球形冲击波与空气折射",
                "空气折射就是背景被空气波扭曲。修改后点预览查看；地面环和马赫杆仍按原代码保持关闭。",
                shockwave, List.of(new Action("冲击波预览", "在前方20格生成半径24格的本地效果；前方区块需已加载。",
                () -> RVP_ShockwaveTuningCommands.previewAtPlayer(24, 0), true))));
        groups = List.copyOf(result);
    }

    /** 通过扩展事件自注册，通用入口不维护具体模块清单。 */
    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用公开的模块登记入口，后续其它调参分类使用相同方式接入。
        event.register(new RVP_ScreenSpaceTuningModule());
    }

    @Override public String id() { return "screen_space"; }
    @Override public String title() { return "屏幕空间特效"; }
    @Override public String description() { return "光照、光晕、喷焰与冲击波；只改变当前客户端的画面。"; }
    @Override public List<Group> groups() { return groups; }

    private static RVP_TuningOption lightOption(RVP_LightRuntimeTuning.Group group, Parameter parameter) {
        // 中文名称只属于具体业务适配器，通用界面不会按这些名字分支。
        String title = switch (parameter) {
            case enabled -> group == RVP_LightRuntimeTuning.Group.engine ? "发动机照亮与外晕开关" : "启用此效果";
            case brightness -> "照亮亮度倍率";
            case radiusScale -> "照亮半径倍率";
            case haloRadiusScale -> "光晕半径倍率";
            case haloShrink -> "光晕收缩强度";
            case skyPeakBrightness -> "天空峰值亮度";
            case skyHoldTicks -> "天空峰值保持刻数";
            case skyFadeTicks -> "天空淡出刻数";
            case skyContract -> "天空收拢幅度";
            case red -> "红色分量倍率";
            case green -> "绿色分量倍率";
            case blue -> "蓝色分量倍率";
            case surface -> "表面受光倍率";
            case decayScale -> "衰减速度倍率";
            case haloDecayCurve -> "光晕亮度衰减曲线";
            case haloPeakHoldTicks -> "光晕高亮保持时间";
            case haloFadeOutTicks -> "光晕独立淡出时间";
            case haloDropFraction -> "快速降亮占寿命比例";
            case haloLowBrightness -> "保持段相对亮度";
            case haloHoldUntil -> "低亮保持至寿命比例";
            case coreIntensity -> group == RVP_LightRuntimeTuning.Group.engine ? "喷焰白热核心亮度" : "爆炸白热核心亮度";
            case haloIntensity -> "喷焰外围橙焰亮度";
            case coreHaloIntensity -> "核心外围光团亮度";
            case coreRadius -> "自发光核心半径";
            case coreRadiusScale -> "核心半径当量倍率";
            case coreShrink -> "核心缩小幅度";
            case coreHaloRadius -> "核心外围光团半径";
            case coreHaloScale -> "核心光团当量缩放";
            case worldHaloIntensity -> "广域光晕独立亮度";
            case inputPeak -> "送入柔化光晕的亮度上限";
            case blurScale -> "光晕柔化范围倍率";
            case nearStrength -> "近层光晕强度倍率";
            case farStrength -> "远层光晕强度倍率";
            case distanceScale -> "环境光观察距离倍率";
            case skyDistance -> "天空散射专用距离";
            case smokeBrightness -> "烟雾额外受光倍率";
        };
        // 直接绑定同一个覆盖表；恢复开关时重新读取 TOML，不能把面板值当成配置默认。
        return new RVP_TuningOption(parameter.name(), title, parameter.description + "；下一显示帧生效。",
                parameter == Parameter.enabled ? "开关" : parameter == Parameter.haloDecayCurve ? "曲线"
                : parameter == Parameter.haloPeakHoldTicks || parameter == Parameter.haloFadeOutTicks
                        || parameter == Parameter.skyHoldTicks || parameter == Parameter.skyFadeTicks
                ? "刻（20刻=1秒）"
                : parameter == Parameter.coreRadius || parameter == Parameter.coreHaloRadius
                        || parameter == Parameter.skyDistance ? "格"
                : parameter == Parameter.coreShrink || parameter == Parameter.coreRadiusScale
                        || parameter == Parameter.coreHaloScale
                        || parameter == Parameter.skyPeakBrightness ? "倍"
                : parameter == Parameter.coreIntensity || parameter == Parameter.haloIntensity
                        || parameter == Parameter.coreHaloIntensity ? "亮度（HDR）" : "系数",
                parameter == Parameter.enabled ? RVP_TuningOption.Control.TOGGLE
                : parameter == Parameter.haloDecayCurve ? RVP_TuningOption.Control.CHOICE : RVP_TuningOption.Control.NUMBER,
                decimal(parameter.min), decimal(parameter.max), 0,
                () -> lightDefault(group, parameter), () -> {
                    Float value = RVP_LightRuntimeTuning.overrides().getOrDefault(group, Map.of()).get(parameter);
                    return value == null ? OptionalDouble.empty() : OptionalDouble.of(decimal(value));
                }, value -> RVP_LightRuntimeTuning.set(group, parameter, (float) value),
                () -> RVP_LightRuntimeTuning.reset(group, parameter), parameter == Parameter.haloDecayCurve
                ? Arrays.stream(RVP_HaloDecayCurve.values()).map(RVP_HaloDecayCurve::title).toList() : List.of());
    }

    private static double lightDefault(RVP_LightRuntimeTuning.Group group, Parameter parameter) {
        // 调用业务默认值入口，分组曲线未覆盖时应在面板中显示全局实际选择。
        if (parameter != Parameter.enabled) { return decimal(RVP_LightRuntimeTuning.defaultValue(group, parameter)); }
        // 调用配置原值，避免临时覆盖污染“恢复默认”提示。
        return (switch (group) {
            case global -> RVP_LightingConfig.configuredScreenEnabled();
            case engine -> RVP_LightingConfig.configuredMissileHaloEnabled();
            default -> RVP_LightingConfig.configuredBloomEnabled();
        }) ? 1 : 0;
    }

    /** 保留业务 float 的十进制语义，避免0.05一类合法输入被二进制精度误判越界。 */
    private static double decimal(float value) { return Double.parseDouble(Float.toString(value)); }

    private static String lightPreview(boolean engine) {
        // 调用正式命令也使用的预览，不经聊天发送命令，不向服务端发包。
        if (RVP_ScreenLightDebugCommands.preview(8, engine, 0.3F, 300) == 0) {
            throw new IllegalStateException("需要先进入世界才能预览。");
        }
        return engine ? "已生成两秒喷焰预览；环境光与外晕仍受各自开关控制。" : "已生成无伤害爆炸光预览。";
    }
}
