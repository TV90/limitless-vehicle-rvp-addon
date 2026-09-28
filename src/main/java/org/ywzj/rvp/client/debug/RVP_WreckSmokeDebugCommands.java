package org.ywzj.rvp.client.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeEmitter;

import java.util.Arrays;
import java.util.List;

/** 注册仅在本地客户端生效的长程击毁烟调试指令。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WreckSmokeDebugCommands {

    /** 准星未命中方块或实体时，预览点沿视线前移的距离，单位格。 */
    private static final double PREVIEW_LOOK_DISTANCE = 6.0;
    /** 指令参数名列表，供客户端输入补全使用。 */
    private static final List<String> PARAMETER_NAMES = Arrays.stream(Parameter.values())
            .map(Parameter::commandName).toList();

    private RVP_WreckSmokeDebugCommands() {
    }

    /** 使用 Forge 客户端指令事件注册根指令，避免远程服务器权限和指令同步的影响。 */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("rvpwrecksmoke")
                .executes(RVP_WreckSmokeDebugCommands::show)
                .then(Commands.literal("show").executes(RVP_WreckSmokeDebugCommands::show))
                .then(Commands.literal("set")
                        .then(Commands.argument("parameter", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(PARAMETER_NAMES, builder))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(RVP_WreckSmokeDebugCommands::set))))
                .then(Commands.literal("reset")
                        .executes(RVP_WreckSmokeDebugCommands::resetAll)
                        .then(Commands.argument("parameter", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(PARAMETER_NAMES, builder))
                                .executes(RVP_WreckSmokeDebugCommands::resetOne)))
                .then(Commands.literal("preview")
                        .then(Commands.literal("start").executes(RVP_WreckSmokeDebugCommands::startPreview))
                        .then(Commands.literal("stop").executes(RVP_WreckSmokeDebugCommands::stopPreview))));
    }

    /** 显示当前客户端全部长程烟参数及使用方法。 */
    private static int show(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("[RVP 击毁烟] /rvpwrecksmoke set <参数> <数值>；"
                + "reset [参数]；preview start|stop"), false);
        for (Parameter parameter : Parameter.values()) {
            // 调用本项目参数格式化入口：在聊天栏展示当前值、单位及代码默认值。
            context.getSource().sendSuccess(() -> Component.literal(parameter.describe()), false);
        }
        return Parameter.values().length;
    }

    /** 验证数值范围后修改本地会话参数。 */
    private static int set(CommandContext<CommandSourceStack> context) {
        // 调用本项目参数查询入口：仅允许修改已登记的长程烟参数。
        Parameter parameter = Parameter.find(StringArgumentType.getString(context, "parameter"));
        if (parameter == null) {
            context.getSource().sendFailure(Component.literal("未知参数；使用 /rvpwrecksmoke show 查看可用名称"));
            return 0;
        }
        try {
            // 调用本项目数值验证入口：拒绝非整数 tick 和超出安全范围的输入。
            parameter.set(DoubleArgumentType.getDouble(context, "value"));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("[RVP 击毁烟] " + parameter.describe()
                + "；发射、寿命和初始外观仅影响新粒子，其余参数也影响已存在的长程烟"), false);
        return 1;
    }

    /** 恢复指定参数的代码默认值。 */
    private static int resetOne(CommandContext<CommandSourceStack> context) {
        // 调用本项目参数查询及重置入口：只恢复指令指定的参数。
        Parameter parameter = Parameter.find(StringArgumentType.getString(context, "parameter"));
        if (parameter == null) {
            context.getSource().sendFailure(Component.literal("未知参数；使用 /rvpwrecksmoke show 查看可用名称"));
            return 0;
        }
        parameter.reset();
        context.getSource().sendSuccess(() -> Component.literal("[RVP 击毁烟] 已恢复 " + parameter.describe()), false);
        return 1;
    }

    /** 恢复全部长程烟参数的代码默认值。 */
    private static int resetAll(CommandContext<CommandSourceStack> context) {
        // 调用本项目参数重置入口：一次恢复所有会话级调试值。
        RVP_WreckSmokeDebugSettings.resetAll();
        context.getSource().sendSuccess(() -> Component.literal("[RVP 击毁烟] 已恢复全部默认值"), false);
        return 1;
    }

    /** 在准星位置开启 60 秒预览，视线没有命中时在前方六格生成。 */
    private static int startPreview(CommandContext<CommandSourceStack> context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            context.getSource().sendFailure(Component.literal("必须进入世界后才能预览击毁烟"));
            return 0;
        }
        HitResult hit = minecraft.hitResult;
        Vec3 center = hit != null && hit.getType() != HitResult.Type.MISS
                ? hit.getLocation() : minecraft.player.getEyePosition().add(
                minecraft.player.getLookAngle().scale(PREVIEW_LOOK_DISTANCE));
        // 调用本项目长程烟预览入口：在准星位置生成与真实残骸共用参数的烟柱。
        RVP_WreckSmokeEmitter.startPreview(level, center);
        context.getSource().sendSuccess(() -> Component.literal("[RVP 击毁烟] 已在准星位置预览 60 秒；"
                + "修改参数会作用于后续发射的烟团"), false);
        return 1;
    }

    /** 停止在准星位置继续生成预览烟团。 */
    private static int stopPreview(CommandContext<CommandSourceStack> context) {
        // 调用本项目预览清理入口：已有烟团自然结束，停止后续发射。
        RVP_WreckSmokeEmitter.stopPreview();
        context.getSource().sendSuccess(() -> Component.literal("[RVP 击毁烟] 已停止预览发射"), false);
        return 1;
    }
}
