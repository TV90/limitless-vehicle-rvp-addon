package org.ywzj.rvp.client.tuning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeEmitter;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/** 延时白烟 F8 分类；绑定业务参数，不修改通用面板、搜索或预设格式。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_MuzzleSmokeTuningModule implements RVP_TuningModule {
    /** 不可变分组，面板据此自动建立控件。 */
    private final List<Group> groups;

    public RVP_MuzzleSmokeTuningModule() {
        List<Group> result = new ArrayList<>();
        for (var group : RVP_DelayedMuzzleSmokeSettings.Group.values()) {
            List<RVP_TuningOption> options = Arrays.stream(Parameter.values())
                    .filter(parameter -> parameter.group == group).map(RVP_MuzzleSmokeTuningModule::option).toList();
            List<Action> actions = group == RVP_DelayedMuzzleSmokeSettings.Group.TIMING ? List.of(
                    new Action("预览45毫米白烟", "前方生成静止炮口白烟，无弹体、无伤害。", () -> preview(45), true),
                    new Action("预览125毫米白烟", "使用正式出烟逻辑，比较口径比例；需要进入世界。", () -> preview(125), true))
                    : List.of();
            result.add(new Group(group.id, group.title,
                    "仅影响45毫米及以上开火白烟；下一次开火或预览生效，已有烟保持原参数。沿用现有贴图。",
                    options, actions));
        }
        groups = List.copyOf(result);
    }

    /** setup 阶段在 MOD 加载总线登记，不能使用尚未启动的游戏总线。 */
    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用既有扩展入口，让面板自动提供搜索、保存、撤销和重置。
        event.register(new RVP_MuzzleSmokeTuningModule());
    }

    @Override public String id() { return "muzzle_smoke"; }
    @Override public String title() { return "延时炮口白烟"; }
    @Override public String description() { return "大口径余烟的节奏、气流、扩散与受光；只改变本机画面。"; }
    @Override public List<Group> groups() { return groups; }

    private static RVP_TuningOption option(Parameter parameter) {
        double scale = parameter.panelScale();
        // 调用同一业务覆盖；面板显示格/秒，写回原指令沿用的格/刻。
        return new RVP_TuningOption(parameter.commandName, parameter.title,
                parameter.description + " 下一次开火或预览生效。"
                        + (parameter.integer ? "整刻/整枚四舍五入。" : "")
                        + (parameter.name().endsWith("_MIN") || parameter.name().endsWith("_MAX")
                        ? "越过区间另一端时，面板同步调整另一端；指令仍严格校验。" : ""),
                parameter.panelUnit(), RVP_TuningOption.Control.NUMBER,
                parameter.minValue * scale, parameter.maxValue * scale, 0,
                () -> parameter.defaultValue * scale,
                () -> {
                    OptionalDouble value = RVP_DelayedMuzzleSmokeSettings.override(parameter);
                    return value.isPresent() ? OptionalDouble.of(value.getAsDouble() * scale) : OptionalDouble.empty();
                }, value -> RVP_DelayedMuzzleSmokeSettings.setFromPanel(parameter, value / scale),
                () -> RVP_DelayedMuzzleSmokeSettings.reset(parameter));
    }

    private static String preview(float caliber) {
        // 调用正式延时队列的本地预览，不发送服务端开火或管理指令。
        RVP_DelayedMuzzleSmokeEmitter.previewAtPlayer(caliber);
        return "已排队" + (int) caliber + "毫米白烟预览；按设定延迟开始，不发射弹体。";
    }
}
