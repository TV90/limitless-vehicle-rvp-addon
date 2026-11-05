package org.ywzj.rvp.client.tuning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/** 击毁长烟、短烟F8分类；通过现有扩展机制获得搜索、保存、撤销和分组操作。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_WreckSmokeTuningModule implements RVP_TuningModule {
    /** 按长烟、短烟排序的固定分组；构造时不写任何参数覆盖。 */
    private final List<Group> groups;

    public RVP_WreckSmokeTuningModule() {
        List<Group> result = new ArrayList<>();
        for (var group : RVP_WreckSmokeDebugSettings.Group.values()) {
            List<RVP_TuningOption> options = Arrays.stream(Parameter.values())
                    .filter(parameter -> parameter.group == group).map(RVP_WreckSmokeTuningModule::option).toList();
            boolean near = group == RVP_WreckSmokeDebugSettings.Group.NEAR_EMISSION;
            List<Action> actions = near || group == RVP_WreckSmokeDebugSettings.Group.LONG_EMISSION ? List.of(
                    new Action(near ? "预览击毁短烟" : "预览击毁长烟", "在准星位置本地出烟60秒，无载具、无伤害。", () -> {
                        // 调用正式烟源的本地预览入口，不向服务器发送击毁或管理命令。
                        return RVP_WreckSmokeEmitter.previewAtCrosshair(near);
                    }, true),
                    new Action("停止击毁烟预览", "停止后续生成，已有烟团自然消散。", () -> {
                        // 调用共用清理入口，只停止预览，不改变真实残骸出烟。
                        RVP_WreckSmokeEmitter.stopPreview();
                        return "已停止击毁烟预览；已有烟团自然消散。";
                    })) : List.of();
            result.add(new Group(group.id, group.title,
                    "仅改变本机击毁烟画面，沿用现有贴图；短烟不影响受损发动机烟，长烟保留原有实时运动调参。",
                    options, actions));
        }
        groups = List.copyOf(result);
    }

    /** setup阶段必须在MOD加载总线登记，此时游戏总线尚未启动。 */
    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用既有调参扩展入口，通用面板无需增加烟雾专用分支。
        event.register(new RVP_WreckSmokeTuningModule());
    }

    @Override public String id() { return "wreck_smoke"; }
    @Override public String title() { return "载具击毁烟"; }
    @Override public String description() { return "长烟柱与发动机附近短烟的节奏、尺寸、消散、颜色和气流。"; }
    @Override public List<Group> groups() { return groups; }

    private static RVP_TuningOption option(Parameter parameter) {
        double scale = parameter.panelScale();
        // 绑定指令实际使用的同一份参数；面板速度显示格/秒，写回内部格/刻。
        return new RVP_TuningOption(parameter.commandName, parameter.title,
                parameter.description + (parameter.liveForExisting() ? " 已有长烟下一刻生效。"
                        : parameter.near() ? " 下一轮生成生效，已有短烟保持出生参数。" : " 下一轮生成或新粒子生效。")
                        + (parameter.integer ? " 整刻四舍五入。" : "")
                        + (parameter.name().endsWith("_MIN") || parameter.name().endsWith("_MAX")
                        ? " 面板越过区间另一端时同步调整另一端；指令严格校验。" : ""),
                parameter.panelUnit(), RVP_TuningOption.Control.NUMBER,
                parameter.minValue * scale, parameter.maxValue * scale, 0,
                () -> parameter.defaultValue * scale,
                () -> {
                    OptionalDouble value = parameter.override();
                    return value.isPresent() ? OptionalDouble.of(value.getAsDouble() * scale) : OptionalDouble.empty();
                }, value -> parameter.setFromPanel(value / scale), parameter::reset);
    }
}
