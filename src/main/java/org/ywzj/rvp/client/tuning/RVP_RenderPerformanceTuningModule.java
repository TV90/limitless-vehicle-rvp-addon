package org.ywzj.rvp.client.tuning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;

import java.util.List;

/** F8性能记录入口；启停使用操作按钮，防止“保存本地”把开启状态带到下次启动。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_RenderPerformanceTuningModule implements RVP_TuningModule {
    /** 唯一固定分组，参数只保存统计间隔，绝不持久化启用状态。 */
    private final List<Group> groups = List.of(new Group("recording", "性能日志开关与统计频率",
            "右键标题选择开始/停止；CPU是提交工作耗时，GPU是显卡执行耗时。父级包含子级，不能重复相加。",
            List.of(new RVP_TuningOption("intervalSeconds", "统计汇总间隔",
                    "每隔多久输出一份汇总；仍逐帧采样。写盘在后台进行，保存此参数不会开启日志。", "秒",
                    RVP_TuningOption.Control.NUMBER, 1, 30, 0, () -> 2,
                    RVP_RenderPerformance::intervalOverride, RVP_RenderPerformance::setInterval,
                    RVP_RenderPerformance::resetInterval)),
            List.of(new Action("开始性能记录", "默认关闭；只写独立rvp-screen-performance.log，不输出性能行到主日志。",
                            RVP_RenderPerformance::start),
                    new Action("停止性能记录", "停止新采样，并异步收尾已提交的显卡结果。", RVP_RenderPerformance::stop),
                    new Action("查看记录状态与文件路径", "显示开关、GPU能力、队列和写盘故障状态。", RVP_RenderPerformance::status))));

    @SubscribeEvent
    public static void register(RVP_RegisterTuningModulesEvent event) {
        // 调用既有MOD加载总线扩展，不修改通用F8布局。
        event.register(new RVP_RenderPerformanceTuningModule());
    }
    @Override public String id() { return "render_performance"; }
    @Override public String title() { return "屏幕特效性能"; }
    @Override public String description() { return "CPU/GPU耗时、绘制和复制工作量；右键分组标题启停独立性能日志。"; }
    @Override public List<Group> groups() { return groups; }
}
