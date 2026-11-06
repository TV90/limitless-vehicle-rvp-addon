package org.ywzj.rvp.client.performance;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

/** 客户端指令和渲染帧事件；不修改本体、不增加Mixin、不向服务器申请记录。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RenderPerformanceClient {
    static {
        // 注册退出刷新；默认关闭时没有文件线程可刷新，也不创建日志文件。
        Runtime.getRuntime().addShutdownHook(new Thread(RVP_RenderPerformance::flushOnExit, "RVP-performance-log-exit"));
    }
    private RVP_RenderPerformanceClient() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFrameStart(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // 调用通用会话入口，GPU结果与完整渲染帧身份一起记录。
            RVP_RenderPerformance.beginFrame();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFrameEnd(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // 调用末端封口，GUI延迟批次已提交后才结束整帧对照。
            RVP_RenderPerformance.endFrame();
        }
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        // 调用本地命令树，开关状态不保存到启动配置；默认每次启动关闭。
        event.getDispatcher().register(Commands.literal("rvprenderperf")
                .executes(context -> reply(context.getSource(), RVP_RenderPerformance.status()))
                .then(Commands.literal("on").executes(context -> reply(context.getSource(), RVP_RenderPerformance.start())))
                .then(Commands.literal("off").executes(context -> reply(context.getSource(), RVP_RenderPerformance.stop())))
                .then(Commands.literal("status").executes(context -> reply(context.getSource(), RVP_RenderPerformance.status())))
                .then(Commands.literal("interval").then(Commands.argument("seconds", DoubleArgumentType.doubleArg(1, 30))
                        .executes(context -> {
                            // 调用与F8共用的统计频率设置，不因改间隔自动开始采样。
                            RVP_RenderPerformance.setInterval(DoubleArgumentType.getDouble(context, "seconds"));
                            return reply(context.getSource(), "性能统计间隔已修改；" + RVP_RenderPerformance.status());
                        })))
                .then(Commands.literal("mark").then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(context -> {
                            // 调用独立日志标记，用于标注近处/远处和不同F8参数的对照阶段。
                            RVP_RenderPerformance.mark(StringArgumentType.getString(context, "text"));
                            return reply(context.getSource(), RVP_RenderPerformance.enabled() ? "已添加性能场景标记。" : "性能记录尚未开启。");
                        }))));
    }

    private static int reply(net.minecraft.commands.CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
