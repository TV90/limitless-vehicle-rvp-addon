package org.ywzj.rvp.guidance;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2COpticalConfidenceFormula;

import java.util.Locale;

/** 在世界运行期间查看、试算或替换图像识别公式。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_OpticalConfidenceCommands {
    private RVP_OpticalConfidenceCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var trial = Commands.literal("test")
                .then(Commands.argument("v", FloatArgumentType.floatArg(0f, 1f))
                        .then(Commands.argument("s", FloatArgumentType.floatArg(0f, 1f))
                                .then(Commands.argument("l", FloatArgumentType.floatArg(0f, 1f))
                                        .executes(RVP_OpticalConfidenceCommands::test))));
        var formula = Commands.literal("formula")
                .executes(context -> show(context.getSource(), RVP_OpticalConfidenceRuntime.server()))
                .then(Commands.literal("set").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("expression", StringArgumentType.greedyString())
                                .executes(RVP_OpticalConfidenceCommands::set)))
                .then(Commands.literal("reset").requires(source -> source.hasPermission(2))
                        .executes(context -> apply(context.getSource(), RVP_OpticalConfidenceFormula.DEFAULT)))
                .then(trial);
        event.getDispatcher().register(Commands.literal("rvp").then(Commands.literal("optical").then(formula)));
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        try {
            // 完整解析成功才替换服务端和各客户端当前使用的公式。
            var formula = RVP_OpticalConfidenceFormula.compile(StringArgumentType.getString(context, "expression"));
            return apply(context.getSource(), formula);
        } catch (IllegalArgumentException error) {
            context.getSource().sendFailure(Component.literal("公式无效。" + error.getMessage()));
            return 0;
        }
    }

    private static int apply(CommandSourceStack source, RVP_OpticalConfidenceFormula formula) {
        RVP_OpticalConfidenceRuntime.setServer(formula);
        // 广播同一表达式，客户端 HUD 和服务端锁定在同一会话内保持一致。
        RVP_Network.CHANNEL.send(PacketDistributor.ALL.noArg(), new S2COpticalConfidenceFormula(formula.source()));
        return show(source, formula);
    }

    private static int show(CommandSourceStack source, RVP_OpticalConfidenceFormula formula) {
        source.sendSuccess(() -> Component.literal("当前图像识别公式为 " + formula.source()), false);
        return 1;
    }

    private static int test(CommandContext<CommandSourceStack> context) {
        float v = FloatArgumentType.getFloat(context, "v");
        float s = FloatArgumentType.getFloat(context, "s");
        float l = FloatArgumentType.getFloat(context, "l");
        // 使用当前权威公式及实际画框门槛，不在世界中创建目标或触发操作。
        float score = RVP_OpticalConfidenceRuntime.server().score(v, s, l);
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "V=%.3f S=%.3f L=%.3f 得分 %.3f，%s", v, s, l, score,
                RVP_OpticalTargeting.canDisplay(score) ? "显示识别框" : "隐藏识别框")), false);
        return 1;
    }

    @SubscribeEvent
    public static void onPlayerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 后加入者直接取得当前开服会话的公式。
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2COpticalConfidenceFormula(RVP_OpticalConfidenceRuntime.server().source()));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // 试调只在本次开服会话生效，不带入另一个世界。
        RVP_OpticalConfidenceRuntime.setServer(RVP_OpticalConfidenceFormula.DEFAULT);
    }
}
