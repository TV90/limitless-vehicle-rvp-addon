package org.ywzj.rvp.client.tuning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.light.RVP_ScreenLightDebugCommands;
import org.ywzj.rvp.light.RVP_RocketPlumeRuntimeTuning;
import org.ywzj.rvp.light.RVP_RocketPlumeRuntimeTuning.Parameter;

import java.util.Arrays;
import java.util.List;

/** 程序尾焰的F8美术分类；通用界面自动提供搜索、滑条、输入、撤销与本地保存。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_RocketPlumeTuningModule implements RVP_TuningModule {
    /** 固定分组；构造和注册均不写覆盖，不预填推荐值。 */
    private final List<Group> groups;

    public RVP_RocketPlumeTuningModule() {
        // 调用业务分组和参数绑定生成控件；这里只登记描述，不把起点值写成用户覆盖。
        groups = Arrays.stream(RVP_RocketPlumeRuntimeTuning.Group.values()).map(group -> new Group(
                group.id, group.title,
                "仅改变本机程序尾焰，下一显示帧生效；亮度和照亮范围在“屏幕空间特效/发动机喷焰与照亮”调节。",
                Arrays.stream(Parameter.values()).filter(parameter -> parameter.group == group)
                        .map(RVP_RocketPlumeTuningModule::option).toList(),
                List.of(new Action("两秒稳态预览", "在准星附近侧向展示尾焰；不发射、不造成伤害。", () -> preview(false), true),
                        new Action("两秒启停预览", "模拟两秒燃烧窗口，观察点火展开与燃尽收束；过长的过渡会重叠。",
                                () -> preview(true), true)))).toList();
    }

    /** setup期在MOD加载总线自注册，避免游戏总线尚未启动时丢失分类。 */
    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用既有扩展登记，让F8自动获得分类，无需给通用面板添加业务判断。
        event.register(new RVP_RocketPlumeTuningModule());
    }

    @Override public String id() { return "rocket_plume"; }
    @Override public String title() { return "程序尾焰"; }
    @Override public String description() { return "导弹和火箭程序喷焰的轮廓、层次、配色、流动与启停；沿用当前效果起点。"; }
    @Override public List<Group> groups() { return groups; }

    private static RVP_TuningOption option(Parameter parameter) {
        // 调用唯一业务覆盖表；关闭面板、命令预览和真实弹体继续读取同一参数。
        return new RVP_TuningOption(parameter.id, parameter.title, parameter.description + " 下一显示帧生效。",
                parameter.unit, RVP_TuningOption.Control.NUMBER, parameter.min, parameter.max, 0,
                () -> parameter.defaultValue, () -> RVP_RocketPlumeRuntimeTuning.override(parameter),
                value -> RVP_RocketPlumeRuntimeTuning.set(parameter, value),
                () -> RVP_RocketPlumeRuntimeTuning.reset(parameter));
    }

    private static String preview(boolean transition) {
        // 调用正式光照命令的同一预览入口，复用场景放置和渲染，不向服务端发送消息。
        if (RVP_ScreenLightDebugCommands.preview(8, true, 0.3F, 300, transition) == 0) {
            throw new IllegalStateException("需要先进入世界才能预览程序尾焰。");
        }
        return transition ? "已生成两秒启停预览；燃尽后自动清理。" : "已生成两秒稳态预览；使用当前尾焰参数。";
    }
}
