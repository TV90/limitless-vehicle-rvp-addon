package org.ywzj.rvp.client.tuning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffController;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/** 载具殉燃F8分类；复用通用搜索、撤销、保存及分组菜单，不给面板增加业务分支。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_WreckCookoffTuningModule implements RVP_TuningModule {
    /** 固定顺序的参数分组；注册时不写入任何覆盖。 */
    private final List<Group> groups;

    public RVP_WreckCookoffTuningModule() {
        List<Group> result = new ArrayList<>();
        for (var group : RVP_WreckCookoffSettings.Group.values()) {
            List<RVP_TuningOption> options = Arrays.stream(Parameter.values())
                    .filter(parameter -> parameter.group == group).map(RVP_WreckCookoffTuningModule::option).toList();
            List<Action> actions = group == RVP_WreckCookoffSettings.Group.SHAPE ? List.of(
                    new Action("预览准星载具殉燃", "瞄准64格内地面载具，按原殉燃时长预览；不击毁、不造成伤害。",
                            RVP_WreckCookoffController::previewAtCrosshair, true),
                    new Action("停止殉燃预览", "只停止本地预览，真实残骸仍按原生命周期燃烧。", () -> {
                        // 调用既有本地控制器，不发送服务器击毁或管理指令。
                        RVP_WreckCookoffController.stopPreview();
                        return "已停止殉燃预览。";
                    })) : List.of();
            result.add(new Group(group.id, group.title,
                    "仅影响本机地面载具殉燃，沿用现有贴图；生成项下一刻生效，已有粒子保留出生参数。",
                    options, actions));
        }
        groups = List.copyOf(result);
    }

    /** setup阶段通过MOD加载总线登记；游戏总线此时尚未启动。 */
    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用既有扩展接口，让通用面板自动获得新分类。
        event.register(new RVP_WreckCookoffTuningModule());
    }

    @Override public String id() { return "wreck_cookoff"; }
    @Override public String title() { return "载具殉燃"; }
    @Override public String description() { return "喷焰强弱、配色、气流与火星冷却、拖影、弹跳；使用现有贴图。"; }
    @Override public List<Group> groups() { return groups; }

    private static RVP_TuningOption option(Parameter parameter) {
        double scale = parameter.panelScale();
        // 调用统一业务参数；只在面板边界将格/刻换成格/秒。
        return new RVP_TuningOption(parameter.commandName, parameter.title,
                parameter.description + " 新生成粒子采用当前值；已有粒子保持出生参数。"
                        + (parameter.integer ? "整刻或整次四舍五入。" : "")
                        + (parameter.name().endsWith("_MIN") || parameter.name().endsWith("_MAX")
                        || parameter == Parameter.ROOF_CONE_BASE || parameter == Parameter.ROOF_CONE_TIP
                        ? "区间越界时联动另一端，仍受各自合法范围限制。" : ""),
                parameter.panelUnit(), parameter.toggle ? RVP_TuningOption.Control.TOGGLE : RVP_TuningOption.Control.NUMBER,
                parameter.minValue * scale, parameter.maxValue * scale, 0,
                () -> parameter.defaultValue * scale,
                () -> {
                    OptionalDouble value = RVP_WreckCookoffSettings.override(parameter);
                    return value.isPresent() ? OptionalDouble.of(value.getAsDouble() * scale) : OptionalDouble.empty();
                }, value -> RVP_WreckCookoffSettings.set(parameter, value / scale),
                () -> RVP_WreckCookoffSettings.reset(parameter));
    }
}
