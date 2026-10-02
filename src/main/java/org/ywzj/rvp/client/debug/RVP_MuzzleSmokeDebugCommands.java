package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.VelocityParameter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** 注册本地客户端的大口径延迟炮口烟调试指令。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_MuzzleSmokeDebugCommands {

    /** 指令参数名列表，供客户端输入补全使用。 */
    private static final List<String> PARAMETER_NAMES = Stream.concat(
            Arrays.stream(Parameter.values()).map(Parameter::commandName),
            Arrays.stream(VelocityParameter.values()).map(VelocityParameter::commandName))
            .toList();

    private RVP_MuzzleSmokeDebugCommands() {
    }

    /** 使用 Forge 客户端指令事件注册本地参数指令，不请求远程服务器权限。 */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("rvpmuzzlesmoke")
                .executes(RVP_MuzzleSmokeDebugCommands::show)
                .then(Commands.literal("show").executes(RVP_MuzzleSmokeDebugCommands::show))
                .then(Commands.literal("set")
                        .then(Commands.argument("parameter", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(PARAMETER_NAMES, builder))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(RVP_MuzzleSmokeDebugCommands::set))))
                .then(Commands.literal("reset")
                        .executes(RVP_MuzzleSmokeDebugCommands::resetAll)
                        .then(Commands.argument("parameter", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(PARAMETER_NAMES, builder))
                                .executes(RVP_MuzzleSmokeDebugCommands::resetOne))));
    }

    /** 显示延迟炮口烟的当前值、默认值和使用方法。 */
    private static int show(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "[RVP 延迟炮口烟] /rvpmuzzlesmoke set <参数> <数值>；reset [参数]"), false);
        for (Parameter parameter : Parameter.values()) {
            // 调用本项目参数格式化入口：向聊天栏展示当前会话参数及默认值。
            context.getSource().sendSuccess(() -> Component.literal(parameter.describe()), false);
        }
        for (VelocityParameter parameter : VelocityParameter.values()) {
            // 调用本项目速度参数格式化入口：向聊天栏展示出膛方向初速度和默认值。
            context.getSource().sendSuccess(() -> Component.literal(parameter.describe()), false);
        }
        return Parameter.values().length;
    }

    /** 验证并修改当前客户端会话中的延迟炮口烟参数。 */
    private static int set(CommandContext<CommandSourceStack> context) {
        String parameterName = StringArgumentType.getString(context, "parameter");
        Parameter parameter = Parameter.find(parameterName);
        VelocityParameter velocityParameter = VelocityParameter.find(parameterName);
        if (parameter == null && velocityParameter == null) {
            context.getSource().sendFailure(Component.literal(
                    "未知参数；使用 /rvpmuzzlesmoke show 查看可用名称"));
            return 0;
        }
        try {
            double value = DoubleArgumentType.getDouble(context, "value");
            if (parameter != null) {
                // 调用本项目 tick 参数校验入口：同时校验整数范围和同组 min/max 关系。
                RVP_DelayedMuzzleSmokeSettings.set(parameter, value);
            } else {
                // 调用本项目速度参数校验入口：校验格/tick 范围和同组 min/max 关系。
                RVP_DelayedMuzzleSmokeSettings.set(velocityParameter, value);
            }
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        String description = parameter != null ? parameter.describe() : velocityParameter.describe();
        context.getSource().sendSuccess(() -> Component.literal(
                "[RVP 延迟炮口烟] 已设置 " + description
                        + "；仅影响之后产生的延迟炮口烟"), false);
        return 1;
    }

    /** 恢复一个参数的默认值。 */
    private static int resetOne(CommandContext<CommandSourceStack> context) {
        String parameterName = StringArgumentType.getString(context, "parameter");
        Parameter parameter = Parameter.find(parameterName);
        VelocityParameter velocityParameter = VelocityParameter.find(parameterName);
        if (parameter == null && velocityParameter == null) {
            context.getSource().sendFailure(Component.literal(
                    "未知参数；使用 /rvpmuzzlesmoke show 查看可用名称"));
            return 0;
        }
        if (parameter != null) {
            // 调用本项目 tick 参数重置入口：必要时同步恢复同组配对参数。
            RVP_DelayedMuzzleSmokeSettings.reset(parameter);
        } else {
            // 调用本项目速度参数重置入口：必要时同步恢复同组配对参数。
            RVP_DelayedMuzzleSmokeSettings.reset(velocityParameter);
        }
        String description = parameter != null ? parameter.describe() : velocityParameter.describe();
        context.getSource().sendSuccess(() -> Component.literal(
                "[RVP 延迟炮口烟] 已恢复 " + description), false);
        return 1;
    }

    /** 恢复所有延迟炮口烟参数的代码默认值。 */
    private static int resetAll(CommandContext<CommandSourceStack> context) {
        // 调用本项目参数重置入口：清理当前客户端会话的全部调试覆盖值。
        RVP_DelayedMuzzleSmokeSettings.resetAll();
        context.getSource().sendSuccess(() -> Component.literal(
                "[RVP 延迟炮口烟] 已恢复全部默认值"), false);
        return 1;
    }
}
