package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffController;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter;

/** 本地殉燃调参；与F8绑定同一参数表，原有命令名称和每刻速度单位保持。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckCookoffDebugCommands {
    private RVP_WreckCookoffDebugCommands() { }

    /** 从业务参数表登记所有参数，不再给面板和指令各维护一份写入器。 */
    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
        for (Parameter parameter : Parameter.values()) {
            // 调用同一范围和写入入口，保留原参数名并自动提供新增项补全。
            set.then(Commands.literal(parameter.commandName)
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(parameter.minValue, parameter.maxValue))
                            .executes(context -> {
                                try {
                                    RVP_WreckCookoffSettings.set(parameter, DoubleArgumentType.getDouble(context, "value"));
                                    return show(context.getSource());
                                } catch (IllegalArgumentException failure) {
                                    context.getSource().sendFailure(Component.literal(failure.getMessage()));
                                    return 0;
                                }
                            })));
        }
        event.getDispatcher().register(Commands.literal("rvpcookoff")
                .executes(context -> show(context.getSource()))
                .then(Commands.literal("show").executes(context -> show(context.getSource())))
                .then(Commands.literal("reset").executes(context -> {
                    // 调用分类业务重置，不影响其它特效或已保存预设。
                    RVP_WreckCookoffSettings.reset();
                    return show(context.getSource());
                }))
                .then(set)
                .then(Commands.literal("preview")
                        .then(Commands.literal("start").executes(context -> {
                            try {
                                // 调用与F8完全相同的本地准星预览，不发送击毁命令。
                                String message = RVP_WreckCookoffController.previewAtCrosshair();
                                context.getSource().sendSuccess(() -> Component.literal("[RVP 殉燃] " + message), false);
                                return 1;
                            } catch (IllegalStateException failure) {
                                context.getSource().sendFailure(Component.literal(failure.getMessage()));
                                return 0;
                            }
                        }))
                        .then(Commands.literal("stop").executes(context -> {
                            // 调用既有停止入口，真实残骸不受预览停止影响。
                            RVP_WreckCookoffController.stopPreview();
                            context.getSource().sendSuccess(() -> Component.literal("[RVP 殉燃] 已停止预览"), false);
                            return 1;
                        }))));
    }

    /** 反馈预算与基本数值，完整中文说明和单位在F8中查看。 */
    private static int show(CommandSourceStack source) {
        // 调用正式控制器的状态描述，显示真实活动实例与预算。
        source.sendSuccess(() -> Component.literal("[RVP 殉燃] " + RVP_WreckCookoffController.describe()), false);
        source.sendSuccess(() -> Component.literal("/rvpcookoff set <参数> <数值>（自动补全，速度用格/刻）；"
                + "reset；preview start|stop；F8 → 载具殉燃可查看全部" + Parameter.values().length + "项并保存本地。"), false);
        return 1;
    }
}
