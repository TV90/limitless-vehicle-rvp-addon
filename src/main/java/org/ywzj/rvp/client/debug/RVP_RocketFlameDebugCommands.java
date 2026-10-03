package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.weapon.visual.RVP_RocketFlameRuntimeTuning;
import org.ywzj.rvp.weapon.visual.RVP_RocketFlameRuntimeTuning.Parameter;

import java.util.Arrays;
import java.util.List;

/** 注册 {@code rvp_rocket_flame} 客户端会话级运行时调参指令。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RocketFlameDebugCommands {

    /** 指令参数名列表，供 Brigadier 输入补全使用。 */
    private static final List<String> PARAMETER_NAMES = Arrays.stream(Parameter.values())
            .map(Parameter::commandName)
            .toList();

    private RVP_RocketFlameDebugCommands() {
    }

    /** 构造挂载到 {@code /rvpdebug} 下的火箭尾焰调参命令树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return buildRoot("rocketFlame");
    }

    /** 注册不依赖服务端权限的本地客户端别名。 */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(buildRoot("rvprocketflame"));
    }

    /** 构造指定根名称的火箭尾焰调参命令树。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String rootName) {
        return Commands.literal(rootName)
                .executes(RVP_RocketFlameDebugCommands::show)
                .then(Commands.literal("status").executes(RVP_RocketFlameDebugCommands::show))
                .then(Commands.literal("set")
                        .then(Commands.argument("parameter", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(PARAMETER_NAMES, builder))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(RVP_RocketFlameDebugCommands::set))))
                .then(Commands.literal("reset")
                        .executes(RVP_RocketFlameDebugCommands::resetAll)
                        .then(Commands.argument("parameter", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(PARAMETER_NAMES, builder))
                                .executes(RVP_RocketFlameDebugCommands::resetOne)));
    }

    /** 显示全部火箭尾焰运行时覆盖值及其默认状态。 */
    private static int show(CommandContext<CommandSourceStack> context) {
        send(context, "[RVP RocketFlame] 当前为客户端会话级覆盖；未覆盖项继续使用武器 JSON/代码默认值。");
        for (Parameter parameter : Parameter.values()) {
            // 调用本项目运行时调参入口：把当前覆盖状态和默认值输出到聊天栏。
            send(context, RVP_RocketFlameRuntimeTuning.describe(parameter));
        }
        return Parameter.values().length;
    }

    /** 解析并修改一个火箭尾焰运行时参数。 */
    private static int set(CommandContext<CommandSourceStack> context) {
        Parameter parameter = Parameter.find(StringArgumentType.getString(context, "parameter"));
        if (parameter == null) {
            sendFailure(context, "未知参数；使用 /rvpdebug rocketFlame status 查看可用名称");
            return 0;
        }
        String rawValue = StringArgumentType.getString(context, "value");
        try {
            // 调用本项目运行时调参入口：校验范围后写入当前客户端会话覆盖值。
            RVP_RocketFlameRuntimeTuning.set(parameter, rawValue);
        } catch (IllegalArgumentException exception) {
            sendFailure(context, exception.getMessage());
            return 0;
        }
        send(context, "[RVP RocketFlame] 已设置 "
                + RVP_RocketFlameRuntimeTuning.describe(parameter)
                + "；只影响之后生成的粒子。");
        return 1;
    }

    /** 恢复一个指定参数的默认状态。 */
    private static int resetOne(CommandContext<CommandSourceStack> context) {
        Parameter parameter = Parameter.find(StringArgumentType.getString(context, "parameter"));
        if (parameter == null) {
            sendFailure(context, "未知参数；使用 /rvpdebug rocketFlame status 查看可用名称");
            return 0;
        }
        // 调用本项目运行时调参入口：清除指定参数覆盖，使其恢复 JSON 或代码默认值。
        RVP_RocketFlameRuntimeTuning.reset(parameter);
        send(context, "[RVP RocketFlame] 已恢复 " + RVP_RocketFlameRuntimeTuning.describe(parameter));
        return 1;
    }

    /** 恢复全部火箭尾焰运行时参数。 */
    private static int resetAll(CommandContext<CommandSourceStack> context) {
        // 调用本项目运行时调参入口：清除当前客户端会话的全部火箭尾焰覆盖值。
        RVP_RocketFlameRuntimeTuning.resetAll();
        send(context, "[RVP RocketFlame] 已恢复全部 JSON/代码默认值。");
        return 1;
    }

    /** 向当前客户端聊天栏发送成功消息。 */
    private static void send(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }

    /** 向当前客户端聊天栏发送失败消息。 */
    private static void sendFailure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message == null ? "参数设置失败" : message));
    }
}
